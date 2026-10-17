package com.quote.manager.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quote.manager.entity.Product;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {
}
