package com.apeadmin.chat.mapper;

import com.apeadmin.chat.domain.ChatSessionDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSessionDO> {
}
