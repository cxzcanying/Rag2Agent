FROM pgvector/pgvector:pg17

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       autoconf automake build-essential ca-certificates git libtool pkg-config \
       postgresql-server-dev-17 \
    && rm -rf /var/lib/apt/lists/*

RUN git init /tmp/scws \
    && git -C /tmp/scws remote add origin https://github.com/hightman/scws.git \
    && git -C /tmp/scws fetch --depth 1 origin b5ae33f6a71ecbf5442a2e485703af4d372f3341 \
    && git -C /tmp/scws checkout --detach FETCH_HEAD \
    && cd /tmp/scws \
    && touch README \
    && sed -i '/^[[:space:]]*#/d' Makefile.am \
    && aclocal \
    && autoconf \
    && autoheader \
    && libtoolize --force --copy \
    && automake --add-missing \
    && ./configure --prefix=/usr/local \
    && make -j"$(nproc)" \
    && make install \
    && rm -rf /tmp/scws

RUN git init /tmp/zhparser \
    && git -C /tmp/zhparser remote add origin https://github.com/amutu/zhparser.git \
    && git -C /tmp/zhparser fetch --depth 1 origin 2e995c4df672563992b4d7a147b8fa2d0d4cda6c \
    && git -C /tmp/zhparser checkout --detach FETCH_HEAD \
    && cd /tmp/zhparser \
    && make -j"$(nproc)" SCWS_HOME=/usr/local \
    && make install SCWS_HOME=/usr/local \
    && rm -rf /tmp/zhparser
