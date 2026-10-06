package com.flowops.modules.auth.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 登录/权限查询（M0 最小实现；M2 起补登录锁定计数、DataScope 明细）。
 * SQL 全部在 XML（resources/mapper/auth/AuthQueryMapper.xml）—— 接口只留签名，
 * 便于与 docs/07 §5.2 的权限模型逐条对照评审。
 */
@Mapper
public interface AuthQueryMapper {

    /** 用户持有的权限点全集（多角色取并集；docs/07 §5.2 的 49 点清单的运行时来源）。 */
    List<String> selectPermissionCodes(@Param("userId") Long userId);

    /** 用户角色编码列表（登录出参 roles 字段）。 */
    List<String> selectRoleCodes(@Param("userId") Long userId);

    /** 用户数据范围类型集合（D-19；多角色由调用方取并集、宽者优先）。 */
    List<String> selectScopeTypes(@Param("userId") Long userId);
}
