/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

package com.aliyun.odps.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.BatchUpdateException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aliyun.odps.OdpsType;
import com.aliyun.odps.TableSchema;
import com.aliyun.odps.data.Record;
import com.aliyun.odps.tunnel.TableTunnel;
import com.aliyun.odps.tunnel.TunnelException;
import com.aliyun.odps.tunnel.io.TunnelRecordWriter;

public class OdpsPreparedStatement extends OdpsStatement implements PreparedStatement {

  private final String TABLE_NAME = "((\\w+\\.)?\\w+)";      // "proj.name" or "name"
  private final String PREP_VALUES = "\\([\\?,?\\s*]+\\)"; // "(?, ?, ?)" or "(?)"
  private final String SPEC_COLS = "\\([\\w+,?\\s*]+\\)";  // "(name1, name2, name3)"

  private final String PREP_INSERT_WITH_SPEC_COLS =
      "(?i)^\\s*insert\\s+into\\s+" + TABLE_NAME + "\\s+" + SPEC_COLS + "\\s+values\\s+"
      + PREP_VALUES + "\\s*;?\\s*$";
  private final String PREP_INSERT_WITH_SPEC_COLS_EXAMPLE =
      "INSERT INTO table (field1, field2) VALUES (?, ?);";

  private final String PREP_INSERT_WITHOUT_SPEC_COLS =
      "(?i)^\\s*insert\\s+into\\s+" + TABLE_NAME + "\\s+values\\s+" + PREP_VALUES + "\\s*;?\\s*$";
  private final String
      PREP_INSERT_WITHOUT_SPEC_COLS_EXAMPLE =
      "INSERT INTO table VALUES (?, ?, ?);";

  /**
   * The prepared sql template (immutable).
   * e.g. insert into table FOO select * from BAR where id = ? and weight = ?
   */
  private final String sql;

  private boolean verified = false;
  private String tableBatchInsertTo;

  private int parametersNum;

  /**
   * The parameters for the prepared sql (index=>parameter).
   * The parameter is stored as Java objects and lazily casted into String when submitting sql.
   * The executeBatch() call will utilize it to upload data to ODPS via tunnel.
   */
  private HashMap<Integer, Object> parameters = new HashMap<Integer, Object>();

  // When addBatch(), compress the parameters into a row
  private List<Object[]> batchedRows = new ArrayList<Object[]>();

  OdpsPreparedStatement(OdpsConnection conn, String sql) {
    super(conn);
    this.sql = sql;

    int counter = 0;
    for( int i = 0; i < sql.length(); i++ ) {
      if( sql.charAt(i) == '?' ) {
        counter++;
      }
    }
    this.parametersNum = counter;
  }

  OdpsPreparedStatement(OdpsConnection conn, String sql, boolean isResultSetScrollable) {
    this(conn, sql);
    this.isResultSetScrollable = isResultSetScrollable;
  }

  @Override
  public void addBatch() throws SQLException {
    Object[] arr = new Object[parametersNum];
    for (int i = 0; i < arr.length; i++) {
      arr[i] = parameters.get(i+1);
    }
    batchedRows.add(arr);
    parameters.clear();
  }

  @Override
  public void clearParameters() throws SQLException {
    parameters.clear();
  }

  @Override
  public void clearBatch() throws SQLException {
    batchedRows.clear();
  }

  /**
   * Only support DML like `INSERT INTO table_name values (e, f, g)` in batch execution
   *
   * Since ODPS SQL does not provide this functionality, we 1) hijack such kind of batched SQLs
   * , 2) assemble the records by ourselves, and 3) call tunnel API to upload them.
   *
   * We verify and parse the SQL to extract table name in the first call to executeBatch(), so that
   * other kinds of statement can be executed in a non-batch way.
   *
   * Considering performance issue, We check it lazily in executeBatch() instead of addBatch().
   *
   * @throws SQLException
   */
  @Override
  public int[] executeBatch() throws SQLException {

    if (!verified) {
      if (!sql.matches(PREP_INSERT_WITHOUT_SPEC_COLS)) {
        throw new SQLException("batched statement only support following syntax: " +
                               PREP_INSERT_WITHOUT_SPEC_COLS_EXAMPLE);
      }

      Matcher matcher = Pattern.compile(PREP_INSERT_WITHOUT_SPEC_COLS).matcher(sql);
      if (matcher.find()) {
        tableBatchInsertTo = matcher.group(1);
      } else {
        throw new SQLException("cannot extract table name in SQL: " + sql);
      }

      verified = true;
    }

    int batchedSize = batchedRows.size();
    // if no sql is batched, just return
    if (batchedSize == 0) {
      return new int[0];
    }

    log.fine(batchedSize + " records are going to be uploaded to table " + tableBatchInsertTo
             + " in batch");

    int[] updateCounts = new int[batchedSize];
    Arrays.fill(updateCounts, -1);

    try {
      TableTunnel.UploadSession session;
      TableTunnel tunnel = new TableTunnel(getConnection().getOdps());

      if (tableBatchInsertTo.contains(".")) {
        String[] splited = tableBatchInsertTo.split("\\.");
        session = tunnel.createUploadSession(splited[0], splited[1]);
      } else {
        String project_name = getConnection().getOdps().getDefaultProject();
        session = tunnel.createUploadSession(project_name, tableBatchInsertTo);
      }
      log.info("create upload session id=" + session.getId());

      TableSchema schema = session.getSchema();
      Record record = session.newRecord();
      int colNum = schema.getColumns().size();
      int valNum = batchedRows.get(0).length;
      if (valNum != colNum) {
        throw new SQLException(
            "the table has " + colNum + " columns, but insert " + valNum + " values");
      }

      long startTime = System.currentTimeMillis();
      TunnelRecordWriter recordWriter = (TunnelRecordWriter) session.openRecordWriter(0, true);
      for (int i = 0; i < batchedSize; i++) {
        Object[] row = batchedRows.get(i);
        for (int j = 0; j < colNum; j++) {
          OdpsType odpsType = schema.getColumn(j).getType();
          Object value = row[j];

          // Set record values with type validation
          switch (odpsType) {
            case BIGINT:
              if (!(value instanceof Long)) {
                throw new BatchUpdateException(
                    j + " col expected to be Long, but: " + value.getClass().getName(),
                    updateCounts);
              }
              record.setBigint(j, (Long) value);
              break;
            case BOOLEAN:
              if (!(value instanceof Boolean)) {
                throw new BatchUpdateException(
                    j + " col expected to be Boolean, but: " + value.getClass().getName(),
                    updateCounts);
              }
              record.setBoolean(j, (Boolean) value);
              break;
            case DATETIME:
              if (!(value instanceof java.util.Date)) {
                throw new BatchUpdateException(
                    j + " col expected to be java.util.Date, but: " + value.getClass().getName(),
                    updateCounts);
              }
              record.setDatetime(j, (java.util.Date) value);
              break;
            case DOUBLE:
              if (!(value instanceof Double)) {
                throw new BatchUpdateException(
                    j + " col expected to be Double, but: " + value.getClass().getName(),
                    updateCounts);
              }
              record.setDouble(j, (Double) value);
              break;
            case STRING:
              if (!(value instanceof byte[])) {
                throw new BatchUpdateException(
                    j + " col expected to be Double, but: " + value.getClass().getName(),
                    updateCounts);
              }
              record.setString(j, (byte[]) value);
              break;
            default:
              throw new RuntimeException("Batch insert do not support data type: " + odpsType);
          }
//          record.set(j, value);   do not check
        }

        recordWriter.write(record);
        updateCounts[i] = 1;
      }

      recordWriter.flush();
      long duration = System.currentTimeMillis() - startTime;
      float megaBytesPerSec = (float) recordWriter.getTotalBytes() / 1024 / 1024 / duration * 1000;
      recordWriter.close();
      log.info(
          String.format("It took me %d ms to insert %d records, %.2f MiB/s", duration, batchedSize,
                        megaBytesPerSec));
      Long[] blocks = new Long[] { Long.valueOf(0) };
      session.commit(blocks);
    } catch (TunnelException e) {
      throw new SQLException(e);
    } catch (IOException e) {
      throw new SQLException(e);
    }

    clearBatch();
    return updateCounts;
  }

  @Override
  public boolean execute() throws SQLException {
    return super.execute(updateSql(sql, parameters));
  }

  @Override
  public ResultSet executeQuery() throws SQLException {
    return super.executeQuery(updateSql(sql, parameters));
  }

  @Override
  public int executeUpdate() throws SQLException {
    return super.executeUpdate(updateSql(sql, parameters));
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public ParameterMetaData getParameterMetaData() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setArray(int parameterIndex, Array x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setAsciiStream(int parameterIndex, InputStream x, int length)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setAsciiStream(int parameterIndex, InputStream x, long length)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBinaryStream(int parameterIndex, InputStream x, int length)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBinaryStream(int parameterIndex, InputStream x, long length)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBlob(int parameterIndex, Blob x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBlob(int parameterIndex, InputStream inputStream, long length)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBytes(int parameterIndex, byte[] x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setCharacterStream(int parameterIndex, Reader reader)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setCharacterStream(int parameterIndex, Reader reader, int length)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setCharacterStream(int parameterIndex, Reader reader, long length)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setClob(int parameterIndex, Clob x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setClob(int parameterIndex, Reader reader) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setClob(int parameterIndex, Reader reader, long length)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNCharacterStream(int parameterIndex, Reader value)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNCharacterStream(int parameterIndex, Reader value, long length)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNClob(int parameterIndex, NClob value) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNClob(int parameterIndex, Reader reader) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNClob(int parameterIndex, Reader reader, long length)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNString(int parameterIndex, String value) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNull(int parameterIndex, int sqlType, String typeName)
      throws SQLException {
    // ODPS doesn't care the type of NULL. So the second parameter is simply ignored.
    parameters.put(parameterIndex, null);
  }

  /**
   * Set an object by inferring the type of the object.
   *
   * @param parameterIndex
   *     the index of parameter
   * @param x
   *     the object to set
   * @throws SQLException
   */
  @Override
  public void setObject(int parameterIndex, Object x) throws SQLException {
    if (x == null) {
      setNull(parameterIndex, Types.NULL);
    } else if (x instanceof String) {
      setString(parameterIndex, (String) x);
    } else if (x instanceof Short) {
      setShort(parameterIndex, (Short) x);
    } else if (x instanceof Integer) {
      setInt(parameterIndex, (Integer) x);
    } else if (x instanceof Long) {
      setLong(parameterIndex, (Long) x);
    } else if (x instanceof Float) {
      setFloat(parameterIndex, (Float) x);
    } else if (x instanceof Double) {
      setDouble(parameterIndex, (Double) x);
    } else if (x instanceof Boolean) {
      setBoolean(parameterIndex, (Boolean) x);
    } else if (x instanceof Byte) {
      setByte(parameterIndex, (Byte) x);
    } else if (x instanceof BigDecimal) {
      setBigDecimal(parameterIndex, (BigDecimal) x);
    } else if (x instanceof Timestamp) {
      setTimestamp(parameterIndex, (Timestamp) x);
    } else if (x instanceof Time) {
      setTime(parameterIndex, (Time) x);
    } else if (x instanceof Date) {
      setDate(parameterIndex, (Date) x);
    } else {
      throw new SQLException("can not set an object of type: " + x.getClass().getName());
    }
  }

  @Override
  public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
    parameters.put(parameterIndex, x);
  }

  @Override
  public void setBoolean(int parameterIndex, boolean x) throws SQLException {
    parameters.put(parameterIndex, new Boolean(x));
  }

  @Override
  public void setByte(int parameterIndex, byte x) throws SQLException {
    parameters.put(parameterIndex, new Long(x));
  }

  public void setDate(int parameterIndex, Date x) throws SQLException {
    parameters.put(parameterIndex, new java.util.Date(x.getTime()));
  }

  @Override
  public void setDouble(int parameterIndex, double x) throws SQLException {
    parameters.put(parameterIndex, new Double(x));
  }

  @Override
  public void setFloat(int parameterIndex, float x) throws SQLException {
    parameters.put(parameterIndex, new Double(x));
  }

  @Override
  public void setInt(int parameterIndex, int x) throws SQLException {
    parameters.put(parameterIndex, new Long(x));
  }

  @Override
  public void setLong(int parameterIndex, long x) throws SQLException {
    parameters.put(parameterIndex, new Long(x));
  }

  @Override
  public void setNull(int parameterIndex, int sqlType) throws SQLException {
    // ODPS doesn't care the type of NULL. So the second parameter is simply ignored.
    parameters.put(parameterIndex, null);
  }

  @Override
  public void setShort(int parameterIndex, short x) throws SQLException {
    parameters.put(parameterIndex, new Long(x));
  }

  @Override
  public void setString(int parameterIndex, String x) throws SQLException {
    parameters.put(parameterIndex, x.getBytes());
  }

  @Override
  public void setTime(int parameterIndex, Time x) throws SQLException {
    parameters.put(parameterIndex, new java.util.Date(x.getTime()));
  }

  @Override
  public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
    parameters.put(parameterIndex, new java.util.Date(x.getTime()));
  }

  @Override
  public void setObject(int parameterIndex, Object x, int targetSqlType)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setRef(int parameterIndex, Ref x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setRowId(int parameterIndex, RowId x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setURL(int parameterIndex, URL x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setUnicodeStream(int parameterIndex, InputStream x, int length)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  /**
   * Returns a new sql replacing the '?'s in the prepared sql to parameters.
   */
  private String updateSql(String sql, HashMap<Integer, Object> parameters) throws SQLException {
    if (!sql.contains("?")) {
      return sql;
    }

    StringBuilder newSql = new StringBuilder(sql);

    int paramIndex = 1;
    int pos = 0;

    while (pos < newSql.length()) {
      if (newSql.charAt(pos) == '?') {
        if (parameters.containsKey(paramIndex)) {
          newSql.deleteCharAt(pos);
          String str = convertJavaTypeToSqlString(parameters.get(paramIndex));
          newSql.insert(pos, str);
          pos += str.length() - 1;
        }
        paramIndex++;
      } else {
        pos++;
      }
    }

    return newSql.toString();
  }

  private String convertJavaTypeToSqlString(Object x) throws SQLException {
    if (x instanceof Long) {
      return x.toString();
    } else if (x instanceof byte[]) {
      try {
        String charset = getConnection().getCharset();
        if (charset != null) {
          return "'" + new String((byte[]) x, charset) + "'";
        } else {
          throw new SQLException("charset is null");
        }
      } catch (UnsupportedEncodingException e) {
        throw new SQLException(e);
      }
    } else if (x instanceof Double) {
      return x.toString();
    } else if (x instanceof java.util.Date) {
      SimpleDateFormat formatter = new SimpleDateFormat(JdbcColumn.ODPS_DATETIME_FORMAT);
      return "cast('" + formatter.format(x) + "' as datetime)";
    } else if (x instanceof Boolean) {
      return x.toString();
    } else if (x instanceof BigDecimal) {
      return "cast('" + x.toString() + "' as decimal)";
    } else if (x == null) {
      return "NULL";
    } else {
      throw new SQLException("unrecognized Java class: " + x.getClass().getName());
    }
  }

}
