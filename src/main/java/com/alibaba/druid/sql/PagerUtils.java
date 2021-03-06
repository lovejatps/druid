/*
 * Copyright 1999-2017 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.sql;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.alibaba.druid.DruidRuntimeException;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.db2.ast.stmt.DB2SelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;
import com.alibaba.druid.sql.dialect.odps.ast.OdpsSelectQueryBlock;
import com.alibaba.druid.sql.dialect.oracle.ast.stmt.OracleSelect;
import com.alibaba.druid.sql.dialect.oracle.ast.stmt.OracleSelectQueryBlock;
import com.alibaba.druid.sql.dialect.oracle.visitor.OracleASTVisitorAdapter;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGSelectQueryBlock;
import com.alibaba.druid.sql.dialect.sqlserver.ast.SQLServerSelectQueryBlock;
import com.alibaba.druid.sql.dialect.sqlserver.ast.SQLServerTop;
import com.alibaba.druid.sql.visitor.SQLASTVisitor;
import com.alibaba.druid.util.JdbcConstants;
import com.alibaba.druid.util.JdbcUtils;

public class PagerUtils {

    public static String count(String sql, String dbType) {
        List<SQLStatement> stmtList = SQLUtils.parseStatements(sql, dbType);

        if (stmtList.size() != 1) {
            throw new IllegalArgumentException("sql not support count : " + sql);
        }

        SQLStatement stmt = stmtList.get(0);

        if (!(stmt instanceof SQLSelectStatement)) {
            throw new IllegalArgumentException("sql not support count : " + sql);
        }

        SQLSelectStatement selectStmt = (SQLSelectStatement) stmt;
        return count(selectStmt.getSelect(), dbType);
    }

    public static String limit(String sql, String dbType, int offset, int count) {
        List<SQLStatement> stmtList = SQLUtils.parseStatements(sql, dbType);

        if (stmtList.size() != 1) {
            throw new IllegalArgumentException("sql not support count : " + sql);
        }

        SQLStatement stmt = stmtList.get(0);

        if (!(stmt instanceof SQLSelectStatement)) {
            throw new IllegalArgumentException("sql not support count : " + sql);
        }

        SQLSelectStatement selectStmt = (SQLSelectStatement) stmt;

        return limit(selectStmt.getSelect(), dbType, offset, count);
    }

    public static String limit(SQLSelect select, String dbType, int offset, int count) {
        SQLSelectQuery query = select.getQuery();

        if (JdbcConstants.ORACLE.equals(dbType)) {
            return limitOracle(select, dbType, offset, count);
        }

        if (JdbcConstants.DB2.equals(dbType)) {
            return limitDB2(select, dbType, offset, count);
        }

        if (JdbcConstants.SQL_SERVER.equals(dbType) || JdbcUtils.JTDS.equals(dbType)) {
            return limitSQLServer(select, dbType, offset, count);
        }

        if (query instanceof SQLSelectQueryBlock) {
            return limitQueryBlock(select, dbType, offset, count);
        }

        throw new UnsupportedOperationException();
    }

    private static String limitQueryBlock(SQLSelect select, String dbType, int offset, int count) {
        SQLSelectQueryBlock queryBlock = (SQLSelectQueryBlock) select.getQuery();
        if (JdbcConstants.MYSQL.equals(dbType) || //
            JdbcConstants.MARIADB.equals(dbType) || //
            JdbcConstants.H2.equals(dbType)) {
            return limitMySqlQueryBlock((MySqlSelectQueryBlock) queryBlock, dbType, offset, count);
        }

        if (JdbcConstants.POSTGRESQL.equals(dbType)) {
            return limitPostgreSQLQueryBlock((PGSelectQueryBlock) queryBlock, dbType, offset, count);
        }
        throw new UnsupportedOperationException();
    }

    private static String limitPostgreSQLQueryBlock(PGSelectQueryBlock queryBlock, String dbType, int offset, int count) {
        if (queryBlock.getLimit() != null) {
            throw new IllegalArgumentException("limit already exists.");
        }

        SQLLimit limit = new SQLLimit();
        if (offset > 0) {
            limit.setOffset(new SQLIntegerExpr(offset));
        }
        limit.setRowCount(new SQLIntegerExpr(count));
        queryBlock.setLimit(limit);

        return SQLUtils.toSQLString(queryBlock, dbType);
    }

    private static String limitDB2(SQLSelect select, String dbType, int offset, int count) {
        SQLSelectQuery query = select.getQuery();

        SQLBinaryOpExpr gt = new SQLBinaryOpExpr(new SQLIdentifierExpr("ROWNUM"), //
                                                 SQLBinaryOperator.GreaterThan, //
                                                 new SQLNumberExpr(offset), //
                                                 JdbcConstants.DB2);
        SQLBinaryOpExpr lteq = new SQLBinaryOpExpr(new SQLIdentifierExpr("ROWNUM"), //
                                                   SQLBinaryOperator.LessThanOrEqual, //
                                                   new SQLNumberExpr(count + offset), //
                                                   JdbcConstants.DB2);
        SQLBinaryOpExpr pageCondition = new SQLBinaryOpExpr(gt, SQLBinaryOperator.BooleanAnd, lteq, JdbcConstants.DB2);

        if (query instanceof SQLSelectQueryBlock) {
            DB2SelectQueryBlock queryBlock = (DB2SelectQueryBlock) query;
            if (offset <= 0) {
                queryBlock.setFirst(new SQLNumberExpr(count));
                return SQLUtils.toSQLString(select, dbType);
            }

            SQLAggregateExpr aggregateExpr = new SQLAggregateExpr("ROW_NUMBER");
            SQLOrderBy orderBy = select.getOrderBy();
            
            if (orderBy == null && select.getQuery() instanceof SQLSelectQueryBlock) {
                SQLSelectQueryBlock selectQueryBlcok = (SQLSelectQueryBlock) select.getQuery();
                orderBy = selectQueryBlcok.getOrderBy();
                selectQueryBlcok.setOrderBy(null);
            } else {
                select.setOrderBy(null);                
            }
            
            aggregateExpr.setOver(new SQLOver(orderBy));

            queryBlock.getSelectList().add(new SQLSelectItem(aggregateExpr, "ROWNUM"));

            DB2SelectQueryBlock countQueryBlock = new DB2SelectQueryBlock();
            countQueryBlock.getSelectList().add(new SQLSelectItem(new SQLAllColumnExpr()));

            countQueryBlock.setFrom(new SQLSubqueryTableSource(select, "XX"));

            countQueryBlock.setWhere(pageCondition);

            return SQLUtils.toSQLString(countQueryBlock, dbType);
        }

        DB2SelectQueryBlock countQueryBlock = new DB2SelectQueryBlock();
        countQueryBlock.getSelectList().add(new SQLSelectItem(new SQLPropertyExpr(new SQLIdentifierExpr("XX"), "*")));
        SQLAggregateExpr aggregateExpr = new SQLAggregateExpr("ROW_NUMBER");
        SQLOrderBy orderBy = select.getOrderBy();
        aggregateExpr.setOver(new SQLOver(orderBy));
        select.setOrderBy(null);
        countQueryBlock.getSelectList().add(new SQLSelectItem(aggregateExpr, "ROWNUM"));

        countQueryBlock.setFrom(new SQLSubqueryTableSource(select, "XX"));

        if (offset <= 0) {
            return SQLUtils.toSQLString(countQueryBlock, dbType);
        }

        DB2SelectQueryBlock offsetQueryBlock = new DB2SelectQueryBlock();
        offsetQueryBlock.getSelectList().add(new SQLSelectItem(new SQLAllColumnExpr()));
        offsetQueryBlock.setFrom(new SQLSubqueryTableSource(new SQLSelect(countQueryBlock), "XXX"));
        offsetQueryBlock.setWhere(pageCondition);

        return SQLUtils.toSQLString(offsetQueryBlock, dbType);
    }

    private static String limitSQLServer(SQLSelect select, String dbType, int offset, int count) {
        SQLSelectQuery query = select.getQuery();

        SQLBinaryOpExpr gt = new SQLBinaryOpExpr(new SQLIdentifierExpr("ROWNUM"), //
                                                 SQLBinaryOperator.GreaterThan, //
                                                 new SQLNumberExpr(offset), //
                                                 JdbcConstants.SQL_SERVER);
        SQLBinaryOpExpr lteq = new SQLBinaryOpExpr(new SQLIdentifierExpr("ROWNUM"), //
                                                   SQLBinaryOperator.LessThanOrEqual, //
                                                   new SQLNumberExpr(count + offset), //
                                                   JdbcConstants.SQL_SERVER);
        SQLBinaryOpExpr pageCondition = new SQLBinaryOpExpr(gt, SQLBinaryOperator.BooleanAnd, lteq,
                                                            JdbcConstants.SQL_SERVER);

        if (query instanceof SQLSelectQueryBlock) {
            SQLServerSelectQueryBlock queryBlock = (SQLServerSelectQueryBlock) query;
            if (offset <= 0) {
                queryBlock.setTop(new SQLServerTop(new SQLNumberExpr(count)));
                return SQLUtils.toSQLString(select, dbType);
            }

            SQLAggregateExpr aggregateExpr = new SQLAggregateExpr("ROW_NUMBER");
            SQLOrderBy orderBy = select.getOrderBy();
            aggregateExpr.setOver(new SQLOver(orderBy));
            select.setOrderBy(null);

            queryBlock.getSelectList().add(new SQLSelectItem(aggregateExpr, "ROWNUM"));

            SQLServerSelectQueryBlock countQueryBlock = new SQLServerSelectQueryBlock();
            countQueryBlock.getSelectList().add(new SQLSelectItem(new SQLAllColumnExpr()));

            countQueryBlock.setFrom(new SQLSubqueryTableSource(select, "XX"));

            countQueryBlock.setWhere(pageCondition);

            return SQLUtils.toSQLString(countQueryBlock, dbType);
        }

        SQLServerSelectQueryBlock countQueryBlock = new SQLServerSelectQueryBlock();
        countQueryBlock.getSelectList().add(new SQLSelectItem(new SQLPropertyExpr(new SQLIdentifierExpr("XX"), "*")));

        countQueryBlock.setFrom(new SQLSubqueryTableSource(select, "XX"));

        if (offset <= 0) {
            countQueryBlock.setTop(new SQLServerTop(new SQLNumberExpr(count)));
            return SQLUtils.toSQLString(countQueryBlock, dbType);
        }

        SQLAggregateExpr aggregateExpr = new SQLAggregateExpr("ROW_NUMBER");
        SQLOrderBy orderBy = select.getOrderBy();
        aggregateExpr.setOver(new SQLOver(orderBy));
        select.setOrderBy(null);
        countQueryBlock.getSelectList().add(new SQLSelectItem(aggregateExpr, "ROWNUM"));

        SQLServerSelectQueryBlock offsetQueryBlock = new SQLServerSelectQueryBlock();
        offsetQueryBlock.getSelectList().add(new SQLSelectItem(new SQLAllColumnExpr()));
        offsetQueryBlock.setFrom(new SQLSubqueryTableSource(new SQLSelect(countQueryBlock), "XXX"));
        offsetQueryBlock.setWhere(pageCondition);

        return SQLUtils.toSQLString(offsetQueryBlock, dbType);
    }

    private static String limitOracle(SQLSelect select, String dbType, int offset, int count) {
        SQLSelectQuery query = select.getQuery();

        if (query instanceof SQLSelectQueryBlock) {
            OracleSelectQueryBlock queryBlock = (OracleSelectQueryBlock) query;
            if (queryBlock.getGroupBy() == null && select.getOrderBy() == null && offset <= 0) {
                SQLExpr condition = new SQLBinaryOpExpr(new SQLIdentifierExpr("ROWNUM"), //
                                                        SQLBinaryOperator.LessThanOrEqual, //
                                                        new SQLNumberExpr(count), //
                                                        JdbcConstants.ORACLE);
                if (queryBlock.getWhere() == null) {
                    queryBlock.setWhere(condition);
                } else {
                    queryBlock.setWhere(new SQLBinaryOpExpr(queryBlock.getWhere(), //
                                                            SQLBinaryOperator.BooleanAnd, //
                                                            condition, //
                                                            JdbcConstants.ORACLE));
                }

                return SQLUtils.toSQLString(select, dbType);
            }
        }

        OracleSelectQueryBlock countQueryBlock = new OracleSelectQueryBlock();
        countQueryBlock.getSelectList().add(new SQLSelectItem(new SQLPropertyExpr(new SQLIdentifierExpr("XX"), "*")));
        countQueryBlock.getSelectList().add(new SQLSelectItem(new SQLIdentifierExpr("ROWNUM"), "RN"));

        countQueryBlock.setFrom(new SQLSubqueryTableSource(select, "XX"));
        countQueryBlock.setWhere(new SQLBinaryOpExpr(new SQLIdentifierExpr("ROWNUM"), //
                                                     SQLBinaryOperator.LessThanOrEqual, //
                                                     new SQLNumberExpr(count + offset), //
                                                     JdbcConstants.ORACLE));
        if (offset <= 0) {
            return SQLUtils.toSQLString(countQueryBlock, dbType);
        }

        OracleSelectQueryBlock offsetQueryBlock = new OracleSelectQueryBlock();
        offsetQueryBlock.getSelectList().add(new SQLSelectItem(new SQLAllColumnExpr()));
        offsetQueryBlock.setFrom(new SQLSubqueryTableSource(new SQLSelect(countQueryBlock), "XXX"));
        offsetQueryBlock.setWhere(new SQLBinaryOpExpr(new SQLIdentifierExpr("RN"), //
                                                      SQLBinaryOperator.GreaterThan, //
                                                      new SQLNumberExpr(offset), //
                                                      JdbcConstants.ORACLE));

        return SQLUtils.toSQLString(offsetQueryBlock, dbType);
    }

    private static String limitMySqlQueryBlock(MySqlSelectQueryBlock queryBlock, String dbType, int offset, int count) {
        if (queryBlock.getLimit() != null) {
            throw new IllegalArgumentException("limit already exists.");
        }

        SQLLimit limit = new SQLLimit();
        if (offset > 0) {
            limit.setOffset(new SQLNumberExpr(offset));
        }
        limit.setRowCount(new SQLNumberExpr(count));
        queryBlock.setLimit(limit);

        return SQLUtils.toSQLString(queryBlock, dbType);
    }

    private static String count(SQLSelect select, String dbType) {
        if (select.getOrderBy() != null) {
            select.setOrderBy(null);
        }

        SQLSelectQuery query = select.getQuery();
        clearOrderBy(query);

        if (query instanceof SQLSelectQueryBlock) {
            SQLSelectItem countItem = createCountItem(dbType);

            SQLSelectQueryBlock queryBlock = (SQLSelectQueryBlock) query;

            if (queryBlock.getGroupBy() != null && queryBlock.getGroupBy().getItems().size() > 0) {
                return createCountUseSubQuery(select, dbType);
            }
            
            int option = queryBlock.getDistionOption();
            if (option == SQLSetQuantifier.DISTINCT && queryBlock.getSelectList().size() == 1) {
                SQLSelectItem firstItem = queryBlock.getSelectList().get(0);
                SQLAggregateExpr exp = new SQLAggregateExpr("COUNT", SQLAggregateOption.DISTINCT);
                exp.addArgument(firstItem.getExpr());
                firstItem.setExpr(exp);
                queryBlock.setDistionOption(0);
            } else {
                queryBlock.getSelectList().clear();
                queryBlock.getSelectList().add(countItem);
            }
            return SQLUtils.toSQLString(select, dbType);
        } else if (query instanceof SQLUnionQuery) {
            return createCountUseSubQuery(select, dbType);
        }

        throw new IllegalStateException();
    }

    private static String createCountUseSubQuery(SQLSelect select, String dbType) {
        SQLSelectQueryBlock countSelectQuery = createQueryBlock(dbType);

        SQLSelectItem countItem = createCountItem(dbType);
        countSelectQuery.getSelectList().add(countItem);

        SQLSubqueryTableSource fromSubquery = new SQLSubqueryTableSource(select);
        fromSubquery.setAlias("ALIAS_COUNT");
        countSelectQuery.setFrom(fromSubquery);

        SQLSelect countSelect = new SQLSelect(countSelectQuery);
        SQLSelectStatement countStmt = new SQLSelectStatement(countSelect);

        return SQLUtils.toSQLString(countStmt, dbType);
    }

    private static SQLSelectQueryBlock createQueryBlock(String dbType) {
        if (JdbcConstants.MYSQL.equals(dbType)) {
            return new MySqlSelectQueryBlock();
        }

        if (JdbcConstants.MARIADB.equals(dbType)) {
            return new MySqlSelectQueryBlock();
        }

        if (JdbcConstants.H2.equals(dbType)) {
            return new MySqlSelectQueryBlock();
        }

        if (JdbcConstants.ORACLE.equals(dbType)) {
            return new OracleSelectQueryBlock();
        }

        if (JdbcConstants.POSTGRESQL.equals(dbType)) {
            return new PGSelectQueryBlock();
        }

        if (JdbcConstants.SQL_SERVER.equals(dbType) || JdbcUtils.JTDS.equals(dbType)) {
            return new SQLServerSelectQueryBlock();
        }

        if (JdbcConstants.DB2.equals(dbType)) {
            return new DB2SelectQueryBlock();
        }

        return new SQLSelectQueryBlock();
    }

    private static SQLSelectItem createCountItem(String dbType) {
        SQLAggregateExpr countExpr = new SQLAggregateExpr("COUNT");

        countExpr.addArgument(new SQLAllColumnExpr());

        SQLSelectItem countItem = new SQLSelectItem(countExpr);
        return countItem;
    }

    private static void clearOrderBy(SQLSelectQuery query) {
        if (query instanceof SQLSelectQueryBlock) {
            SQLSelectQueryBlock queryBlock = (SQLSelectQueryBlock) query;
            if (queryBlock instanceof MySqlSelectQueryBlock) {
                MySqlSelectQueryBlock mysqlQueryBlock = (MySqlSelectQueryBlock) queryBlock;
                if (mysqlQueryBlock.getOrderBy() != null) {
                    mysqlQueryBlock.setOrderBy(null);
                }
            } else if (queryBlock instanceof PGSelectQueryBlock) {
                PGSelectQueryBlock pgQueryBlock = (PGSelectQueryBlock) queryBlock;
                if (pgQueryBlock.getOrderBy() != null) {
                    pgQueryBlock.setOrderBy(null);
                }
            }
            return;
        }

        if (query instanceof SQLUnionQuery) {
            SQLUnionQuery union = (SQLUnionQuery) query;
            if (union.getOrderBy() != null) {
                union.setOrderBy(null);
            }
            clearOrderBy(union.getLeft());
            clearOrderBy(union.getRight());
        }
    }
    
    /**
     * 
     * @param sql
     * @param dbType
     * @return if not exists limit, return -1;
     */
    public static int getLimit(String sql, String dbType) {
        List<SQLStatement> stmtList = SQLUtils.parseStatements(sql, dbType);

        if (stmtList.size() != 1) {
            return -1;
        }

        SQLStatement stmt = stmtList.get(0);

        if (stmt instanceof SQLSelectStatement) {
            SQLSelectStatement selectStmt = (SQLSelectStatement) stmt;
            SQLSelectQuery query = selectStmt.getSelect().getQuery();
            if (query instanceof SQLSelectQueryBlock) {
                if (query instanceof MySqlSelectQueryBlock) {
                    SQLLimit limit = ((MySqlSelectQueryBlock) query).getLimit();

                    if (limit == null) {
                        return -1;
                    }

                    SQLExpr rowCountExpr = limit.getRowCount();

                    if (rowCountExpr instanceof SQLNumericLiteralExpr) {
                        int rowCount = ((SQLNumericLiteralExpr) rowCountExpr).getNumber().intValue();
                        return rowCount;
                    }

                    return Integer.MAX_VALUE;
                }

                if (query instanceof OdpsSelectQueryBlock) {
                    SQLLimit limit = ((OdpsSelectQueryBlock) query).getLimit();
                    SQLExpr rowCountExpr = limit != null ? limit.getRowCount() : null;

                    if (rowCountExpr instanceof SQLNumericLiteralExpr) {
                        int rowCount = ((SQLNumericLiteralExpr) rowCountExpr).getNumber().intValue();
                        return rowCount;
                    }

                    return Integer.MAX_VALUE;
                }

                return -1;
            }
        }
        
        return -1;
    }

    public static boolean hasUnorderedLimit(String sql, String dbType) {
        List<SQLStatement> stmtList = SQLUtils.parseStatements(sql, dbType);

        if (JdbcConstants.MYSQL.equals(dbType)) {

            MySqlUnorderedLimitDetectVisitor visitor = new MySqlUnorderedLimitDetectVisitor();

            for (SQLStatement stmt : stmtList) {
                stmt.accept(visitor);
            }

            return visitor.unorderedLimitCount > 0;
        }

        if (JdbcConstants.ORACLE.equals(dbType)) {

            OracleUnorderedLimitDetectVisitor visitor = new OracleUnorderedLimitDetectVisitor();

            for (SQLStatement stmt : stmtList) {
                stmt.accept(visitor);
            }

            return visitor.unorderedLimitCount > 0;
        }

        throw new DruidRuntimeException("not supported. dbType : " + dbType);
    }

    private static class MySqlUnorderedLimitDetectVisitor extends MySqlASTVisitorAdapter {
        public int unorderedLimitCount;

        @Override
        public boolean visit(MySqlSelectQueryBlock x) {
            SQLOrderBy orderBy = x.getOrderBy();
            SQLLimit limit = x.getLimit();

            if (limit != null && (orderBy == null || orderBy.getItems().size() == 0)) {
                unorderedLimitCount++;
            }
            return true;
        }
    }

    private static class OracleUnorderedLimitDetectVisitor extends OracleASTVisitorAdapter {
        public int unorderedLimitCount;

        public boolean visit(SQLBinaryOpExpr x) {
            SQLExpr left = x.getLeft();
            SQLExpr right = x.getRight();

            boolean rownum = false;
            if (left instanceof SQLIdentifierExpr
                    && ((SQLIdentifierExpr) left).getName().equalsIgnoreCase("ROWNUM")
                    && right instanceof SQLLiteralExpr) {
                rownum = true;
            } else if (right instanceof SQLIdentifierExpr
                    && ((SQLIdentifierExpr) right).getName().equalsIgnoreCase("ROWNUM")
                    && left instanceof SQLLiteralExpr) {
                rownum = true;
            }

            OracleSelectQueryBlock selectQuery = null;
            if (rownum) {
                for (SQLObject parent = x.getParent(); parent != null; parent = parent.getParent()) {
                    if (parent instanceof SQLSelectQuery) {
                        if (parent instanceof OracleSelectQueryBlock) {
                            OracleSelectQueryBlock queryBlock = (OracleSelectQueryBlock) parent;
                            if (queryBlock.getFrom() instanceof SQLExprTableSource) {
                                selectQuery = queryBlock;
                            }
                        }
                        break;
                    }
                }
            }

            if (selectQuery != null) {
                SQLObject parent = selectQuery.getParent();
                if (parent instanceof SQLSelect) {
                    SQLSelect select = (SQLSelect) parent;
                    if (select.getOrderBy() == null || select.getOrderBy().getItems().size() == 0) {
                        unorderedLimitCount++;
                    }
                }
            }

            return true;
        }

        @Override
        public boolean visit(OracleSelectQueryBlock queryBlock) {
            boolean isExprTableSrc = queryBlock.getFrom() instanceof SQLExprTableSource;

            if (!isExprTableSrc) {
                return true;
            }

            boolean rownum = false;
            for (SQLSelectItem item : queryBlock.getSelectList()) {
                SQLExpr itemExpr = item.getExpr();
                if (itemExpr instanceof SQLIdentifierExpr) {
                    if (((SQLIdentifierExpr) itemExpr).getName().equalsIgnoreCase("ROWNUM")) {
                        rownum = true;
                        break;
                    }
                }
            }

            if (!rownum) {
                return true;
            }

            SQLObject parent = queryBlock.getParent();
            if (!(parent instanceof SQLSelect)) {
                return true;
            }

            SQLSelect select = (SQLSelect) parent;

            if (select.getOrderBy() == null || select.getOrderBy().getItems().size() == 0) {
                unorderedLimitCount++;
            }

            return false;
        }
    }
}
