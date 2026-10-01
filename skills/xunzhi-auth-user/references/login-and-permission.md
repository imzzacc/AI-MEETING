# 登录与权限

## 登录相关接口

- `POST /api/xunzhi/v1/users/login`
- `GET /api/xunzhi/v1/users/check-login`
- `POST /api/xunzhi/v1/users/logout`
- `GET /api/xunzhi/v1/users/is-admin`
- `POST /api/xunzhi/v1/users/admin`

## 登录返回语义

用户名注册可用性以 `t_user` 的实际记录为准，不能把布隆过滤器的命中当作已存在：过滤器可能误判，也可能残留数据库回滚/重建前的记录。`GET /users/has-username` 保留历史行为，`true` 表示可注册、`false` 表示已占用（接口名称容易造成误解，不可仅按名字反转返回值）。检查不排除软删除记录，因为数据库唯一键仍保留该用户名。

注册在获取用户名锁后查库，再由数据库唯一键兜底并发冲突。锁占用返回“处理中，请稍后重试”，不能假报“用户名已存在”。登录按用户名和密码匹配失败时统一返回“用户名或密码错误”，不能仅凭匹配失败断言用户不存在。以上为2026-10-02修复，不改用户密码或重建任何现有账号。

登录成功后会返回：

- `token`
- `username`
- `isAdmin`

`check-login` 会返回：

- `isLogin`
- 如果已登录，还会带 `username` 和 `token`

## LoginSessionService 语义

- `login(username)`：建立登录态。
- `getCurrentToken()`：取当前 token。
- `getCurrentLoginId()`：取当前 loginId。
- `logoutByToken(token)`：按 token 登出。
- `getTokenTimeout(token)`：返回 token 剩余时间；无效 token 返回 `-2`。

## 权限语义

- `PermissionService.isAdmin(username)` 最终走 `AdminPermissionService.isAdmin(username)`。
- `POST /users/admin` 受 `@SaCheckRole("admin")` 保护。
- 所以“是否管理员”是后端运行时判断，不应由前端自行缓存为可信事实。

## 一个实用判断

- 登录成功不等于拿到了会话归属权限。
- `isAdmin = true` 只说明管理员角色成立，不代表可以跳过业务域里的归属校验。
