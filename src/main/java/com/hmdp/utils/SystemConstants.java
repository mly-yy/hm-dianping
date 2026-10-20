package com.hmdp.utils;

public class SystemConstants {
    /**
     * 探店笔记照片的上传目录（必须指向 nginx 前端站点下的 imgs 目录）
     *
     * 为什么必须是这个路径（照片地址的完整链路）：
     *  1. POST /upload/blog 把图片写到本目录，返回相对文件名，形如 /blogs/8/3/xxx.jpg
     *  2. 数据库 tb_blog.images 存的就是这个相对路径（多张用逗号分隔）
     *  3. 前端页面（blog-detail.html、index.html）拼成 /imgs/blogs/8/3/xxx.jpg 请求图片
     *  4. nginx 端口8080 的 root 指向 html/hmdp，所以 /imgs/... 实际映射到
     *     nginx安装目录\html\hmdp\imgs\...
     *  5. 因此上传目录必须和 nginx 的 root 对得上，否则图片能存进去但访问是404
     *
     * 原来这里是课程作者的路径 D:\lesson\nginx-1.18.0\...，本机不存在，
     * 上传会直接抛 DirectoryNotFoundException，已改成本机真实路径。
     */
    public static final String IMAGE_UPLOAD_DIR = "D:\\develop\\nginx-1.18.0\\html\\hmdp\\imgs\\";
    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;
}
