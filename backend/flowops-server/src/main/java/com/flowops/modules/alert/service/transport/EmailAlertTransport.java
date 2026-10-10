package com.flowops.modules.alert.service.transport;

import com.flowops.domain.entity.alert.AlertChannel;
import com.flowops.domain.service.AlertTransport;
import org.springframework.stereotype.Component;

/**
 * EMAIL 渠道占位实现（M4-S4，README-M4 O-65）。
 *
 * <p><b>一期不接 SMTP</b>：server 无 spring-boot-starter-mail 依赖，且引入 SMTP
 * 需要主机/端口/发件账号的配置面与连通性测试面，超出本切片。渠道配置数据面
 * （endpoint/secret/enabled/verified）完备，规则可引用 EMAIL —— 投递与测试均返回
 * {@code ok=false, error="SMTP 未接入"}，在记录的 deliver_result 里如实可见。</p>
 */
@Component
public class EmailAlertTransport implements AlertTransport {

    static final String NOT_IMPLEMENTED = "SMTP 投递一期未接入（EMAIL 渠道配置保留）";

    @Override
    public String channelType() {
        return "EMAIL";
    }

    @Override
    public SendResult send(AlertChannel channel, String title, String content) {
        return new SendResult(false, NOT_IMPLEMENTED, 0);
    }
}
