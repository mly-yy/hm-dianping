package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 根据id查询探店笔记详情
     * @param id 笔记id
     * @return 笔记详情（含作者信息与当前用户是否点赞）
     */
    Result queryBlogById(Long id);

    /**
     * 点赞 / 取消点赞（同一个接口，第二次调用即取消）
     * @param id 笔记id
     * @return 无
     */
    Result likeBlog(Long id);

    /**
     * 点赞排行榜：查询给该笔记点赞的用户，按点赞时间先后排序（前5名）
     * 底层用 Redis SortedSet 存储，score 就是点赞时间戳
     * @param id 笔记id
     * @return 点赞用户列表（排行榜顺序）
     */
    Result queryBlogLikes(Long id);

    Result saveBlog(Blog blog);

    Result queryBlogOfFollow(Long max, Integer offset);
}
