package cn.sql.cloud.sql;

/**
 * SQL接口，提供一个统一标准，屏蔽各数据库差别
 * @author TQ
 *
 */
public interface ISQL {

	/**
	 * 获取驱动类
	 * @return
	 */
	String getDirverClass();
	
	/**
	 * 获取JDBC-URL
	 * @return
	 */
	String getURL(String host, int port, String database);
	
	/**
	 * 获取查询数据库中表的语句 Columns[tableName,createTime,updateTime,tableComment]
	 * @param database
	 * @return
	 */
	String getSQLTables(String database);
	
	/**
	 * 获取查询列的语句
	 * @param database
	 * @param tableName
	 * @return
	 */
	String getSQLColumns(String database, String tableName);
	
	/**
	 * 每页默认条数100
	 * @return 条数
	 */
	default int getPageSize() {
		return 100;
	}
	
	/**
	 * 将SQL添加分页语句
	 * @param sql
	 * @param pageNo 页码 [1,2,3,...]
	 * @return
	 */
	default String pageSQL(String sql, int pageNo) {
		throw new java.lang.UnsupportedOperationException("分页方法未实现");
	}

}
