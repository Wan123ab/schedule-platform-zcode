package com.flowops.domain.mapper.concurrency;

import com.flowops.domain.dto.query.CredentialDispatchRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 下发期凭据查询（仅调度器使用；SQL 见 XML）。 */
@Mapper
public interface CredentialQueryMapper {

    /** 节点绑定的凭据投影（不含指纹等展示字段——下发路径只要连接三要素）。 */
    CredentialDispatchRow findCredentialForDispatch(@Param("nodeId") Long nodeId);
}
