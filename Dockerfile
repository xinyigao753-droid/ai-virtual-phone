# 腾讯云 CloudBase / 云托管通用入口
# 仅负责打包和启动原有 Next.js 项目，不改动业务代码。

FROM node:22-bookworm-slim AS build

WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
# 自部署/腾讯云 APK 站点不走作者激活码门禁；必须在 Next 构建阶段注入，
# 仅在运行阶段设置会来不及写入前端 bundle。
ARG NEXT_PUBLIC_SELF_HOSTED_MODE=true
ENV NEXT_PUBLIC_SELF_HOSTED_MODE=$NEXT_PUBLIC_SELF_HOSTED_MODE

COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM node:22-bookworm-slim AS runtime

WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV HOST=0.0.0.0
ENV NEXT_PUBLIC_SELF_HOSTED_MODE=true

COPY --from=build /app/package*.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/.next ./.next
COPY --from=build /app/app ./app
COPY --from=build /app/components ./components
COPY --from=build /app/lib ./lib
COPY --from=build /app/public ./public
COPY --from=build /app/data ./data
COPY --from=build /app/scripts ./scripts
COPY --from=build /app/styles ./styles
COPY --from=build /app/middleware.ts ./middleware.ts
COPY --from=build /app/next.config.mjs ./next.config.mjs
COPY --from=build /app/tsconfig.json ./tsconfig.json

CMD ["npm", "start"]
