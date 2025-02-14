package com.adrninistrator.jacg.handler.method;

import com.adrninistrator.jacg.common.DC;
import com.adrninistrator.jacg.common.JACGConstants;
import com.adrninistrator.jacg.common.enums.DbInsertMode;
import com.adrninistrator.jacg.common.enums.DbTableInfoEnum;
import com.adrninistrator.jacg.common.enums.SqlKeyEnum;
import com.adrninistrator.jacg.conf.ConfigureWrapper;
import com.adrninistrator.jacg.dboper.DbOperWrapper;
import com.adrninistrator.jacg.dto.write_db.WriteDbData4MethodCall;
import com.adrninistrator.jacg.handler.base.BaseHandler;
import com.adrninistrator.jacg.util.JACGClassMethodUtil;
import com.adrninistrator.jacg.util.JACGUtil;
import com.adrninistrator.javacg.common.JavaCGConstants;
import com.adrninistrator.javacg.common.enums.JavaCGCallTypeEnum;
import com.adrninistrator.javacg.common.enums.JavaCGYesNoEnum;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author adrninistrator
 * @date 2023/3/13
 * @description: 方法调用处理类
 */
public class MethodCallHandler extends BaseHandler {
    private static final Logger logger = LoggerFactory.getLogger(MethodCallHandler.class);

    protected AtomicBoolean runningFlag1 = new AtomicBoolean(false);
    protected AtomicBoolean runningFlag2 = new AtomicBoolean(false);

    public MethodCallHandler(ConfigureWrapper configureWrapper) {
        super(configureWrapper);
    }

    public MethodCallHandler(DbOperWrapper dbOperWrapper) {
        super(dbOperWrapper);
    }

    /**
     * 启用指定的方法调用
     *
     * @param methodCallId
     * @return
     */
    public boolean enableMethodCall(int methodCallId) {
        return updateMethodCallEnabled(methodCallId, JavaCGYesNoEnum.YES.getIntValue());
    }

    /**
     * 禁用指定的方法调用
     *
     * @param methodCallId
     * @return
     */
    public boolean disableMethodCall(int methodCallId) {
        return updateMethodCallEnabled(methodCallId, JavaCGYesNoEnum.NO.getIntValue());
    }

    // 修改方法调用表启用标志
    private boolean updateMethodCallEnabled(int methodCallId, int enabled) {
        if (!runningFlag1.compareAndSet(false, true)) {
            logger.error("当前方法不允许并发调用调用");
            return false;
        }

        try {
            SqlKeyEnum sqlKeyEnum = SqlKeyEnum.MC_UPDATE_STATUS;
            String sql = dbOperWrapper.getCachedSql(sqlKeyEnum);
            if (sql == null) {
                sql = "update " + DbTableInfoEnum.DTIE_METHOD_CALL.getTableName() +
                        " set " + DC.MC_ENABLED + " = ?" +
                        " where " + DC.MC_CALL_ID + " = ?";
                sql = dbOperWrapper.cacheSql(sqlKeyEnum, sql);
            }

            int row = dbOperator.update(sql, new Object[]{enabled, methodCallId});
            logger.info("修改方法调用表 {} 启用标志: {} 行数: {}", methodCallId, enabled, row);
            return row > 0;
        } finally {
            runningFlag1.set(false);
        }
    }

    /**
     * 人工向方法调用表写入数据
     * 在原有向数据库写入数据操作完成之后执行
     * 使用自定义框架导致方法调用关系在字节码中缺失时使用，例如使用XML、注解等方式的情况
     *
     * @param callerFullMethod 调用者完整方法
     * @param calleeFullMethod 被调用者完整方法
     * @return
     */
    public boolean manualAddMethodCall(String callerFullMethod, String calleeFullMethod) {
        if (StringUtils.isAnyBlank(callerFullMethod, calleeFullMethod)) {
            logger.error("调用方法与被调用方法不允许为空 {} {}", callerFullMethod, calleeFullMethod);
            return false;
        }

        if (StringUtils.equals(callerFullMethod, calleeFullMethod)) {
            logger.error("调用方法与被调用方法不允许相同 {}", callerFullMethod);
            return false;
        }

        if (!runningFlag2.compareAndSet(false, true)) {
            logger.error("当前方法不允许并发调用调用");
            return false;
        }

        try {
            // 查询数据库方法调用表最大序号
            int maxCallId = dbOperWrapper.getMaxMethodCallId();
            if (maxCallId == JACGConstants.MAX_METHOD_CALL_ID_ILLEGAL) {
                return false;
            }

            String callerClassName = JACGClassMethodUtil.getClassNameFromMethod(callerFullMethod);
            String calleeClassName = JACGClassMethodUtil.getClassNameFromMethod(calleeFullMethod);
            logger.info("人工向数据库方法调用表加入数据 {} {} {}", maxCallId + 1, callerFullMethod, calleeFullMethod);
            // 人工向方法调用表写入数据，行号使用0，jar包序号使用0
            WriteDbData4MethodCall writeDbData4MethodCall = WriteDbData4MethodCall.genInstance(JavaCGCallTypeEnum.CTE_MANUAL_ADDED.getType(),
                    "",
                    dbOperWrapper.getSimpleClassName(callerClassName),
                    callerFullMethod,
                    dbOperWrapper.getSimpleClassName(calleeClassName),
                    calleeFullMethod,
                    ++maxCallId,
                    JavaCGConstants.DEFAULT_LINE_NUMBER,
                    String.valueOf(0),
                    "",
                    ""
            );
            Object[] arguments = JACGUtil.genMethodCallObjectArray(writeDbData4MethodCall);

            String sql = dbOperWrapper.genAndCacheInsertSql(DbTableInfoEnum.DTIE_METHOD_CALL.getSqlKey(),
                    DbTableInfoEnum.DTIE_METHOD_CALL.getSqlKey4Print(),
                    DbInsertMode.DIME_INSERT,
                    DbTableInfoEnum.DTIE_METHOD_CALL.getTableName(),
                    DbTableInfoEnum.DTIE_METHOD_CALL.getColumns());
            return dbOperator.insert(sql, arguments);
        } finally {
            runningFlag2.set(false);
        }
    }
}
