# AI Meeting 前端

AI Meeting 的 Web 前端，基于 [lishuangqiang/AI-Meeting-Frontend](https://github.com/lishuangqiang/AI-Meeting-Frontend)（MIT）。项目整体介绍见[根目录 README](../README.md)。

## 技术栈

React 19 · TypeScript 5.9 · Vite 7 · Tailwind CSS 3（shadcn/ui）· Redux Toolkit · TanStack Query · React Router 7 · Vitest

## 本地开发

```bash
cp .env.example .env
npm ci
npm run dev
```

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `VITE_API_BASE_URL` | `/api` | 接口前缀 |
| `VITE_API_TARGET` | `http://localhost:8002` | 开发服务器把 `/api` 代理到这个后端地址 |
| `VITE_WS_BASE_URL` | 空 | 语音 WebSocket 地址，留空时按当前页面地址推导 |

## 常用命令

| 命令 | 说明 |
| --- | --- |
| `npm run dev` | 启动开发服务器 |
| `npm run check` | lint + 类型检查 + 单元测试，CI 也跑这个 |
| `npm run build` | 生产构建 |
| `npm run test` | vitest 监听模式 |

## 目录

```text
src/
├── app/          # 路由、全局 Provider
├── pages/        # 页面：登录、对话、面试、面试报告、题库
├── components/   # 组件，按业务域分目录
├── hooks/        # 业务逻辑 hooks（面试流程、语音、简历分析等）
├── services/     # 接口封装
├── store/        # Redux 状态
├── lib/ config/ types/
```

## 自适应面试相关

在原有面试页面的基础上增加了：

- 开始前选择面试时长，以及岗位和技术版本范围；
- 面试中显示剩余时间和时间提示，超时后可以延期或结束（`InterviewTimePanel`）；
- 报告页显示面经生成状态，支持重试、导出 Markdown，以及错题的搜索、筛选、复习、纠正和移除（`AdaptiveReviewPanel`）；
- 刷新后先从服务端恢复题目和计时，恢复完成前不允许作答。

接口封装在 `services/adaptiveInterviewService.ts`。

## License

MIT，见 [LICENSE](LICENSE)。
