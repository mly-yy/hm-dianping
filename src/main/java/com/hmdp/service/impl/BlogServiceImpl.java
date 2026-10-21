package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IFollowService followService;

    /**
     * 根据id查询探店笔记详情
     * 返回内容包含三部分：
     * 1. 笔记本身（tb_blog）
     * 2. 作者信息（name、icon，来自 tb_user，Blog实体里这两个字段标注了 @TableField(exist = false)）
     * 3. 当前登录用户是否已点赞（isLike），未登录时为 false
     */
    @Override
    public Result queryBlogById(Long id) {
        //1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        //2.查询blog有关的用户
        queryBlogUser(blog);
        //3.查询blog是否被当前用户点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 点赞 / 取消点赞（同一个接口，第二次调用即取消）
     *
     * 【点赞排行榜的核心】这里用的是 Redis 的 SortedSet(ZSet)，不是 Set！
     *   key    = blog:liked:{blogId}
     *   member = userId          （谁点的赞）
     *   score  = 点赞时的时间戳   （什么时候点的赞）
     *
     * 为什么必须用 ZSet：
     *   Set 是无序的，取出来的用户顺序不确定，做不了"排行榜"；
     *   ZSet 会按 score 自动排序，score 存时间戳，就天然实现了
     *   "按点赞时间先后排列"的点赞排行榜。
     *
     * 判断是否点过赞：ZSCORE key userId
     *   返回 null  -> 没点过赞
     *   返回分数   -> 点过赞（分数就是点赞时间）
     *
     * 切换逻辑（同一个按钮点第二次即取消）：
     *   原来没有分数 -> ZADD 加入，数据库 liked + 1
     *   原来有分数   -> ZREM 移除，数据库 liked - 1
     */
    @Override
    @Transactional
    public Result likeBlog(Long id) {
        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        //2.判断当前用户是否已经点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            //3.如果未点赞，可以点赞
            //3.1 数据库点赞数 +1
            //   IFNULL 兜底：tb_blog.liked 列允许为 NULL，NULL + 1 仍然是 NULL
            boolean isSuccess = update()
                    .setSql("liked = IFNULL(liked, 0) + 1")
                    .eq("id", id)
                    .update();
            //3.2 保存用户到Redis的ZSet集合
            //   score 用当前时间戳，这样排行榜就是按点赞时间排序
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            //4.如果已点赞，取消点赞
            //4.1 数据库点赞数 -1
            boolean isSuccess = update()
                    .setSql("liked = IFNULL(liked, 0) - 1")
                    .eq("id", id)
                    .update();
            //4.2 把用户从Redis的ZSet集合移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 点赞排行榜：查询给该笔记点赞的用户，按点赞时间先后排序
     *
     * 实现要点：
     * 1. ZRANGE key 0 4 —— 取 score（时间戳）升序的前5个，即"最早点赞的5个人"，
     *    这就是排行榜的排名依据
     * 2. 拿到 userId 集合后批量查用户，但 SQL 的 WHERE id IN (...) 不会保持我们给的顺序，
     *    所以必须用 ORDER BY FIELD(id, ...) 把顺序强制拉回来，
     *    否则前端头像顺序会和排行榜顺序不一致
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        //1.查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //4.返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败");
        }
        //查询笔记作者的所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //推送笔记id给所有粉丝
        for(Follow follow:follows){
            Long userId= follow.getUserId();
            //推送
            String key = "feed:"+userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询收件箱
        String key = "feed:"+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //3.非空判断
        if(typedTuples==null||typedTuples.isEmpty()){
            return Result.ok();
        }
        //4.解析数据：blogId，score（时间戳），offset
        List<Long>ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple:typedTuples){
            //4.1获取id
            String idStr = tuple.getValue();
            ids.add(Long.valueOf(idStr));

            //4.2获取分数
            long time = tuple.getScore().longValue();
            if (time==minTime){
                os++;
            }else {
                minTime = time;
                os=1;
            }
        }
        //4.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();
        for (Blog blog : blogs) {
            //5.1查询blog有关的用户
            queryBlogUser(blog);
            //5.2查询blog是否被点赞
            isBlogLiked(blog);
        }
        //5.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

    /**
     * 查询blog的作者信息，填充到 blog 的 name / icon 字段
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        if (user == null) {
            //作者可能已被删除，不能直接 user.getNickName() 否则NPE
            return;
        }
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 判断当前登录用户是否给该笔记点过赞，设置 blog.isLike
     * 未登录（UserHolder里没有用户）时保持 false
     *
     * 注意：改成 ZSet 后，判断方式从 SISMEMBER 换成了 ZSCORE：
     *   ZSCORE 返回 null   -> 没点过赞
     *   ZSCORE 返回分数    -> 点过赞
     */
    private void isBlogLiked(Blog blog) {
        //1.获取登录用户（未登录时直接返回，避免NPE）
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        Long userId = user.getId();
        //2.判断当前用户是否已经点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //3.设置isLike
        blog.setIsLike(score != null);
    }
}
