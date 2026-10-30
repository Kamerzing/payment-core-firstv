FROM postgres:18.6-alpine
RUN apk add --no-cache restic
USER postgres
ENTRYPOINT ["restic"]
