# Contributing

Thanks for contributing to XunZhi Frontend.

## Before you start

- Read the README to understand the repository layout and environment variables.
- Open an issue before large refactors or feature work so implementation direction is aligned.
- Keep changes scoped. Infrastructure cleanup and feature changes should be submitted separately when possible.

## Local workflow

1. Use Node.js 20+ and npm 10+.
2. Copy `.env.example` to `.env.development` and fill in backend endpoints if needed.
3. Install dependencies with `npm install`.
4. Start local development with `npm run dev`.
5. Run `npm run check` before opening a pull request.

## Commit and pull request expectations

- Commit messages must follow Conventional Commits because `commitlint` runs in the repo hooks.
- Include tests or explain why test coverage is not applicable.
- Keep pull requests focused on one problem area.
- Use the PR template and fill in the validation section.

## Code conventions

- Route-level pages assemble UI; complex flow logic belongs in hooks and services.
- Shared HTTP behavior goes through `src/lib/request.ts`.
- Runtime environment parsing goes through `src/config/env.ts`.
- App-wide providers are registered in `src/app/providers.tsx`.
- Prefer adding or updating tests when touching shared logic or stateful flows.

## Review checklist

- `npm run lint`
- `npm run typecheck`
- `npm run test:ci`
- `npm run build`

If any item cannot be run in your environment, call it out in the pull request.
