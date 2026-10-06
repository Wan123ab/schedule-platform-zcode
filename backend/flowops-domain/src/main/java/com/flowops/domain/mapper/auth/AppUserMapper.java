package com.flowops.domain.mapper.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.auth.AppUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {
}
