-- ===========================================================================
-- schema.sql —— 每次应用启动都会执行（application.yaml 里 spring.sql.init.mode=always）
--
-- ⚠️ 所以这个文件里**只能放幂等的语句**。
--    CREATE TABLE IF NOT EXISTS 是幂等的，跑一百遍结果一样；
--    ALTER TABLE ... ADD COLUMN 不是 —— 第二次跑就报 Duplicate column name，
--    而 spring.sql.init 默认不吞异常，结果是**应用直接起不来**。
--    （这个坑真踩过：把一句 ALTER 写在这里，第二次启动就炸了。）
--
-- 增量改动请放到 db/migration/ 下面，手动执行一次，不要加进这个文件。
-- 想要自动化就上 Flyway / Liquibase，它们各自有版本表来保证只跑一次 ——
-- 而 schema.sql 是没有"跑过没有"这个概念的，它每次无条件重放。
--
-- 分界线很简单：
--   **新表**   → 写在这个文件里。CREATE TABLE IF NOT EXISTS 是幂等的，
--                每次启动自动补建，老库也跟着长出来，不需要迁移脚本。
--   **改旧表** → 写进 db/migration/。ALTER 不幂等，重放必炸。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 基础表：db_account / db_account_details / db_topic_type / db_topic
--
-- ⚠️ 这四张表原先**没有任何建表语句进过仓库** —— 它们是在开发早期手工建的，
--    只存在于本机那个 test 库里。后果是：换一台机器（或 Docker 起一个空库）
--    应用启动时只能建出下面那三张新表，然后登录查 db_account 直接报表不存在。
--    补进来之后，"一个空库 + 启动应用"就能得到完整可用的库。
--
--    字段是照着实体类（entity/dto/*.java）和线上库两处核对过的，
--    不是照抄某个教程。
-- ---------------------------------------------------------------------------

-- 账号主体。对应 Account 实体。
--
-- id 早先是 `int(10) unsigned zerofill`，这次**去掉了 zerofill**，理由：
--   1. 实体类里是 `Integer id`，zerofill 只影响显示（把 1 显示成 0000000001），
--      读写靠隐式转换照样能用 —— 也就是说它从来没起过作用，只是看着吓人
--   2. zerofill 在 MySQL 8.0.17 起被标记为 deprecated，将来会被移除
--   3. 它还会让 SHOW CREATE TABLE / 导出结果里 id 和别的表对不上，误导人
-- 去掉它不改变任何一行的实际数值（1 还是 1），只去掉前导零。
CREATE TABLE IF NOT EXISTS db_account (
    id                INT          NOT NULL AUTO_INCREMENT,
    username          VARCHAR(255)          DEFAULT NULL,
    password          VARCHAR(255)          DEFAULT NULL COMMENT 'BCrypt 哈希，不是明文',
    email             VARCHAR(255)          DEFAULT NULL,
    role              VARCHAR(255)          DEFAULT NULL,
    registration_date DATETIME              DEFAULT NULL,
    PRIMARY KEY (id),
    -- 唯一索引不只是"防重"：它是注册时并发抢同一个用户名的最后一道防线。
    -- 应用层"先查有没有再插入"中间有窗口（本项目在点赞那里被咬过一次），
    -- 所以真正兜底的是这两个索引。
    UNIQUE KEY unique_name (username),
    UNIQUE KEY unique_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号';

-- 账号资料。和 db_account 是 1:1，用**同一个 id** 关联，不单开关联列：
--   db_account_details.id  ==  db_account.id
--
-- 有意**没有**外键约束（全项目都没有）。好处是删号/建号顺序自由、灌测试数据方便；
-- 代价是删 db_account 不会连带删这张，得靠应用层手工级联。
--
-- id 显式设成主键：它本来就是"一个账号最多一行资料"这条规则的载体，
-- 加主键等于让数据库也认这条规则，而不只是应用层假设。
CREATE TABLE IF NOT EXISTS db_account_details (
    id               INT         NOT NULL,
    gender           TINYINT              DEFAULT NULL COMMENT '0男 1女 2直升机（前端三选一）',
    phone            VARCHAR(255)         DEFAULT NULL,
    qq               VARCHAR(255)         DEFAULT NULL,
    wx               VARCHAR(255)         DEFAULT NULL COMMENT '实体类没映射它，保留原样',
    description      VARCHAR(255)         DEFAULT NULL COMMENT '个人简介，纯文本展示',
    avatar           VARCHAR(255)         DEFAULT NULL COMMENT '存 db_image_storage.image_key，不是 URL',
    -- 隐私开关。DEFAULT 1 是刻意的：升级那一刻所有老用户的资料在全公开状态，
    -- 默认 0 会让他们的主页**同时**变空 —— 那是事故，不是功能。
    show_gender      TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '是否公开性别',
    show_phone       TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '是否公开手机号',
    show_qq          TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '是否公开 QQ',
    show_description TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '是否公开简介',
    show_topics      TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '是否公开我发的帖子列表',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号资料与隐私开关';

-- 帖子类型（版块）。对应 TopicType 实体。
--
-- 列名叫 description 而不是 desc：DESC 是 MySQL 保留字，
-- MyBatis-Plus 生成的 SQL 不带反引号，`SELECT id, name, desc` 会直接语法错误。
-- 早先那列就叫 desc，是后来改名的。
CREATE TABLE IF NOT EXISTS db_topic_type (
    id          INT          NOT NULL AUTO_INCREMENT,
    name        VARCHAR(255)          DEFAULT NULL COMMENT '类型名，前端下拉框显示的就是它',
    description VARCHAR(255)          DEFAULT NULL COMMENT '类型说明，给以后做版块简介留的',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子类型（版块）';

-- 帖子。对应 Topic 实体。
CREATE TABLE IF NOT EXISTS db_topic (
    id            INT      NOT NULL AUTO_INCREMENT,
    title         VARCHAR(255) NOT NULL,
    -- 列名就叫 type。TYPE 不是 MySQL 保留字（DESC 才是），所以不用加反引号。
    type          INT      NOT NULL COMMENT '对应 db_topic_type.id',
    -- 存 Quill 输出的 HTML（服务端已按白名单清洗过），不是纯文本。
    -- 图片以 <img src="/api/image/{key}"> 嵌在里面，所以**图文关系是隐式的** ——
    -- 想知道一个帖子用了哪些图就得解析这段 HTML（这也是"图片清不掉"的根因）。
    content       MEDIUMTEXT         DEFAULT NULL,
    uid           INT      NOT NULL COMMENT '作者 db_account.id',
    time          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    comment_count INT      NOT NULL DEFAULT 0 COMMENT '评论数（冗余计数，避免每次 COUNT(*)）',
    like_count    INT      NOT NULL DEFAULT 0 COMMENT '点赞数（冗余计数，也是本项目第一个写热点）',
    PRIMARY KEY (id),
    -- 两个复合索引对应列表页那两种筛选：按作者、按分类，
    -- 且都是"筛完再按时间倒序"，所以把 time 放进同一个索引里避免 filesort。
    KEY idx_uid_time (uid, time),
    KEY idx_type_time (type, time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子';

-- ---------------------------------------------------------------------------
-- 下面三张是后来加的表。新表放这里（CREATE TABLE IF NOT EXISTS 幂等，每次启动重放无害）；
-- 改**已有**表的 ALTER 必须放 db/migration/，因为 ALTER 不幂等，重放会让应用起不来。
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS db_image_storage (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    uid           INT          NOT NULL                COMMENT '上传者 db_account.id',
    image_key     VARCHAR(64)  NOT NULL                COMMENT 'UUID，同时是取图 URL 里的标识',
    original_name VARCHAR(255) NOT NULL DEFAULT ''     COMMENT '用户上传时的原始文件名',
    content_type  VARCHAR(64)  NOT NULL                COMMENT '服务端按文件头判定的类型，不信客户端',
    size          INT          NOT NULL                COMMENT '字节数',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    UNIQUE KEY uk_image_key (image_key),
    KEY idx_uid_created (uid, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='论坛图片';

CREATE TABLE IF NOT EXISTS db_comment (
    id       INT AUTO_INCREMENT PRIMARY KEY,
    topic_id INT      NOT NULL                COMMENT '所属帖子 db_topic.id',
    uid      INT      NOT NULL                COMMENT '作者 db_account.id',
    content  TEXT     NOT NULL                COMMENT '已清洗过的 HTML，前端直接 v-html',
    time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发表时间',
    -- (topic_id, id) 而不是只索引 topic_id：
    -- 评论列表是 WHERE topic_id = ? AND id < ? ORDER BY id DESC，
    -- 复合索引让这三件事一次走完，不用回表排序、也不用 filesort
    KEY idx_topic_id (topic_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子评论（平铺，无楼中楼）';

CREATE TABLE IF NOT EXISTS db_topic_like (
    uid        INT      NOT NULL COMMENT '点赞的人 db_account.id',
    topic_id   INT      NOT NULL COMMENT '被点赞的帖子 db_topic.id',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 复合主键，不是 id + 唯一索引。两个理由：
    --   1. 它**就是**那条业务规则"一个人对一条帖子只能点一次赞"，
    --      数据库层面直接保证，应用层不用再先查有没有、再插（那中间有并发窗口）
    --   2. 主键本身是索引，顺带就是"查我有没有点过这条"的索引，不用再建一个
    PRIMARY KEY (uid, topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子点赞';
