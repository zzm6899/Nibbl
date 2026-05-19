FROM node:22-alpine

WORKDIR /app
ENV NODE_ENV=production
ARG NIBBL_BUILD_SHA=local
ARG NIBBL_BUILD_TIME=
ENV NIBBL_BUILD_SHA=$NIBBL_BUILD_SHA
ENV NIBBL_BUILD_TIME=$NIBBL_BUILD_TIME

COPY deploy/backend/package*.json ./
RUN npm ci --omit=dev

COPY deploy/backend/src ./src
COPY deploy/backend/public ./public

EXPOSE 8090
CMD ["node", "src/server.js"]
