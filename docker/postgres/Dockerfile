FROM pgvector/pgvector:pg17

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       autoconf automake build-essential ca-certificates clang-19 git libtool pkg-config \
       postgresql-server-dev-17 \
    && rm -rf /var/lib/apt/lists/*

RUN git clone --depth 1 https://github.com/hightman/scws.git /tmp/scws \
    && git -C /tmp/scws checkout b5ae33f6a71ecbf5442a2e485703af4d372f3341 \
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

RUN git clone --depth 1 https://github.com/amutu/zhparser.git /tmp/zhparser \
    && git -C /tmp/zhparser checkout 2e995c4df672563992b4d7a147b8fa2d0d4cda6c \
    && cd /tmp/zhparser \
    && make -j"$(nproc)" SCWS_HOME=/usr/local \
    && make install SCWS_HOME=/usr/local \
    && rm -rf /tmp/zhparser
