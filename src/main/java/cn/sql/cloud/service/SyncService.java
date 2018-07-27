package cn.sql.cloud.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import cn.sql.cloud.entity.JDBC;
import cn.sql.cloud.entity.Sync;
import cn.sql.cloud.entity.meta.Column;
import cn.sql.cloud.entity.meta.Table;
import cn.sql.cloud.entity.meta.TypeInfo;
import cn.sql.cloud.exception.SQLCloudException;
import cn.sql.cloud.jdbc.JDBCManager;
import cn.sql.cloud.jdbc.JDBCMapper;
import cn.sql.cloud.jdbc.SQLRunner;
import cn.sql.cloud.sql.ISQL;
import cn.sql.cloud.sql.SQLManager;
import cn.sql.cloud.utils.SQLCloudUtils;

/**
 * 数据库同步 Service
 * 
 * @author TQ
 *
 */
@Service("syncService")
//使用到实例对象,需要考虑线程安全问题，不使用单例
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class SyncService {
	// log
	final static Logger logger = LoggerFactory.getLogger(SyncService.class);
	/**
	 * 批量同步的数据量
	 */
	public final static int SYNC_BATCH_SIZE = 1000;
	/**
	 * 源连接
	 */
	private Connection connsrc = null;
	/**
	 * 目标连接
	 */
	private Connection conndes = null;

	private JDBC jdbcsrc;
	private JDBC jdbcdes;

	private ISQL sqlsrc;
	private ISQL sqldes;
	/**
	 * 目标连接的数据类型信息
	 */
	private List<TypeInfo> typeInfosDest = Collections.emptyList();

	/**
	 * 初始化连接信息
	 * 
	 * @param sync
	 */
	private void initConn(Sync sync) {
		String username = sync.getUsername(), src = sync.getSrc(), dest = sync.getDest();
		connsrc = JDBCManager.getConnection(username, src);
		conndes = JDBCManager.getConnection(username, dest);
		jdbcsrc = JDBCManager.getJdbcByName(username, src);
		jdbcdes = JDBCManager.getJdbcByName(username, dest);

		sqlsrc = SQLManager.getSQL(jdbcsrc.getSqlType());
		sqldes = SQLManager.getSQL(jdbcdes.getSqlType());
		
		try {
			try(ResultSet rs = conndes.getMetaData().getTypeInfo()){
				typeInfosDest = JDBCMapper.resultSet2List(rs, TypeInfo.class);
			}
			//设置为手动提交
			conndes.setAutoCommit(false);
		} catch (SQLException e) {
			logger.error(e.getMessage());
		}
	}

	/**
	 * 释放连接
	 */
	private void release() {
		JDBCManager.close(connsrc);
		JDBCManager.close(conndes);
		typeInfosDest = null;
		connsrc = null;
		conndes = null;
	}

	/**
	 * 同步数据库对象
	 * 
	 * @param sync
	 *            数据库同步信息
	 */
	public void run(Sync sync) {
		try {
			initConn(sync);
			List<Table> tables = sqlsrc.getTables(jdbcsrc.getDatabase(), connsrc);
			for (Table table : tables) {
				String tableName = table.getName();
				List<Column> columns = syncStruct(tableName, sync);
				if(!columns.isEmpty()) {
					syncData(tableName, columns);
				}
			}
		} catch (SQLException e) {
			logger.error(e.getMessage());
			throw new SQLCloudException(e);
		} finally {
			release();
		}
	}
	
	/**
	 * 同步表数据
	 * @param tableName
	 * @param columns
	 * @throws SQLException
	 */
	private void syncData(String tableName, List<Column> columns) throws SQLException {
		String insertSQL = insertSQL(tableName, columns);
		//预编译INSERT语句
		try (PreparedStatement prest = conndes.prepareStatement(insertSQL)) {
			String select = selectSQL(tableName);
			int totalPN = getTotalPageNo(select);
			try (Statement stmt = connsrc.createStatement()) {
				//分页
				for (int pageNo = 1; pageNo <= totalPN; pageNo++) {
					String pageSQL = sqlsrc.pageSQL(select, pageNo, SYNC_BATCH_SIZE);
					try (ResultSet rs = stmt.executeQuery(pageSQL)) {
						while (rs.next()) {
							for (int i = 0, l = columns.size(); i < l; i++) {
								String columnName = columns.get(i).getName();
								prest.setObject(i + 1, rs.getObject(columnName));
							}
							prest.addBatch();
							prest.executeBatch();
							conndes.commit();
						}
					}
				}
			}
		}
	}
	
	/**
	 * 同步表结构，返回列信息，创建失败返回空集合
	 * @param tableName
	 * @return
	 * @throws SQLException 
	 */
	private List<Column> syncStruct(String tableName, Sync sync) throws SQLException{
		List<Column> columns = sqldes.getColumns(jdbcdes.getDatabase(), tableName, conndes);
		// 如果表存在，强制同步表结构为true，删除表
		if (columns.isEmpty() == false && sync.isForce()) {
			SQLRunner.executeUpdate(dropTableSQL(tableName), conndes);
		}
		// 如果列为空或强制同步表结构，新建表
		if (columns.isEmpty() || sync.isForce()) {
			columns = sqlsrc.getColumns(jdbcsrc.getDatabase(), tableName, connsrc);
			try {
				SQLRunner.executeUpdate(createTableSQL(tableName, columns), conndes);
			} catch (Exception e) {
				logger.error("创建表失败:tableName:{}, errmsg:{}", tableName, e.getMessage());
				return Collections.emptyList();
			}
		}
		return columns;
	}
	
	/**
	 * 获取源数据总页数
	 * @param sql
	 * @return
	 */
	private int getTotalPageNo(String sql) {
		String countSQL = SQLCloudUtils.parseCountSQL(sql);
		int total = SQLRunner.executeQuery(countSQL, Integer.class, connsrc).get(0);
		if(total < SYNC_BATCH_SIZE) {
			return 1;
		}
		int totalPN = total / SYNC_BATCH_SIZE;
		return totalPN % SYNC_BATCH_SIZE == 0 ? totalPN : totalPN + 1;
	}
	
	/**
	 * 根据jdbcType获取数据库中类型信息
	 * @param jdbcType {@link java.sql.Types}
	 * @return
	 */
	@Nullable
	private TypeInfo getTypeInfoByJdbcType(int jdbcType) {
		for(TypeInfo typeInfo:typeInfosDest) {
			if(typeInfo.getDataType() == jdbcType) {
				return typeInfo;
			}
		}
		return null;
	}

	/**
	 * 根据表名生成 查询语句
	 * @param tableName 表名
	 * @return select * from tableName
	 */
	private String selectSQL(String tableName) {
		return new StringBuilder("SELECT * FROM ").append(tableName).toString();
	}
	
	/**
	 * 删除表
	 * @param tableName
	 * @return
	 */
	private String dropTableSQL(String tableName) {
		return new StringBuilder("DROP TABLE ").append(tableName).toString();
	}

	/**
	 * 构建 create table 语句
	 * @param tableName 表名
	 * @param columns 列集合
	 * @return create table 语句
	 */
	private String createTableSQL(String tableName, List<Column> columns) {
		return new StringBuilder("CREATE TABLE ").append(tableName).append("(")
				.append(String.join(",", columns.stream().map(c -> {
					StringBuilder fieldSql = new StringBuilder();
					fieldSql.append(c.getName());
					fieldSql.append(" ");
					TypeInfo typeInfo = getTypeInfoByJdbcType(c.getDataType());
					if (typeInfo == null) {
						fieldSql.append("BLOB");
					} else {
						fieldSql.append(typeInfo.getLocalTypeName());
						// [(M[,D])] [UNSIGNED] [ZEROFILL]
						String createParams = typeInfo.getCreateParams();
						if (StringUtils.isNotBlank(createParams)) {
							int M = c.getColumnSize();// 精度
							int D = c.getDecimalDigits();// 标度
							if (createParams.contains("M")) {
								if (M == 0) {
									M = typeInfo.getPrecision();
								}
								createParams = createParams.replace("M", String.valueOf(M));
							}
							if (createParams.contains(",D")) {
								if (D == 0) {
									D = typeInfo.getMinimumScale();
								}
								createParams = createParams.replace(",D", "," + D);
							}
							createParams = createParams.replaceAll("[^0-9,()]", "");
							fieldSql.append(createParams);
						}
					}

					return fieldSql.toString();
				}).collect(Collectors.toList()))).append(")").toString();
	}

	/**
	 * 创建 insert 语句
	 * @param tableName 表名
	 * @param columns 列信息
	 * @return insert 语句
	 */
	private String insertSQL(String tableName, List<Column> columns) {
		return new StringBuilder("INSERT INTO ").append(tableName).append("(")
				.append(String.join(",", columns.stream().map(c -> c.getName()).collect(Collectors.toList())))
				.append(") ").append("VALUES(").append(String.join(",", Collections.nCopies(columns.size(), "?")))
				.append(")").toString();
	}

}
