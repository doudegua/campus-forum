/**
 * 默认头像。放在 public/ 下，Vite 会把它映射到根路径 ——
 * 所以这里写的是 /default-avatar.png 而不是 import 一个文件。
 * <p>
 * 原来指向 cube.elemecdn.com，是拿第三方 CDN 当兜底：
 * 那个域名哪天挂掉/被墙，全站没设头像的人一起变裂图，
 * 而且每次都是跨域请求。
 */
export const defaultAvatar = '/default-avatar.png'