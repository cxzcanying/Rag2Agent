<script setup>
import { computed, onMounted, ref } from 'vue';

const loading = ref(true);
const health = ref(null);
const version = ref(null);
const providers = ref([]);
const error = ref('');

const statusLabel = computed(() => {
  if (loading.value) {
    return '检查中';
  }
  return health.value?.data?.status === 'UP' ? '运行中' : '未连接';
});

const statusClass = computed(() => (health.value?.data?.status === 'UP' ? 'status-ok' : 'status-warn'));

async function fetchJson(path) {
  const response = await fetch(path);
  if (!response.ok) {
    throw new Error(`${path} returned ${response.status}`);
  }
  return response.json();
}

async function refreshStatus() {
  loading.value = true;
  error.value = '';
  try {
    const [healthResponse, versionResponse, providersResponse] = await Promise.all([
      fetchJson('/api/health'),
      fetchJson('/api/version'),
      fetchJson('/api/ai/providers'),
    ]);
    health.value = healthResponse;
    version.value = versionResponse;
    providers.value = providersResponse.data ?? [];
  } catch (caught) {
    health.value = null;
    version.value = null;
    providers.value = [];
    error.value = caught instanceof Error ? caught.message : 'unknown error';
  } finally {
    loading.value = false;
  }
}

onMounted(refreshStatus);
</script>

<template>
  <main class="layout">
    <section class="topbar">
      <div>
        <p class="eyebrow">RAG2Agent</p>
        <h1>企业级 RAG + Agent 工程骨架</h1>
      </div>
      <button type="button" class="refresh-button" @click="refreshStatus">刷新</button>
    </section>

    <section class="status-grid">
      <article class="panel">
        <span class="label">后端状态</span>
        <strong :class="statusClass">{{ statusLabel }}</strong>
        <p v-if="error" class="muted">错误：{{ error }}</p>
        <p v-else class="muted">接口：/api/health</p>
      </article>

      <article class="panel">
        <span class="label">版本</span>
        <strong>{{ version?.data?.version ?? '-' }}</strong>
        <p class="muted">{{ version?.data?.name ?? '等待后端响应' }}</p>
      </article>

      <article class="panel">
        <span class="label">模块</span>
        <strong>{{ version?.data?.modules?.length ?? 0 }}</strong>
        <p class="muted">{{ version?.data?.modules?.join(' / ') ?? '暂无数据' }}</p>
      </article>
    </section>

    <section class="providers">
      <div class="section-heading">
        <h2>AI Provider</h2>
        <span>{{ providers.length }} 个配置</span>
      </div>
      <div class="provider-list">
        <article v-for="provider in providers" :key="provider.name" class="provider-row">
          <div>
            <strong>{{ provider.name }}</strong>
            <p>{{ provider.baseUrl }}</p>
          </div>
          <span :class="provider.enabled ? 'pill enabled' : 'pill'">
            {{ provider.enabled ? '已启用' : '未启用' }}
          </span>
        </article>
        <p v-if="!providers.length" class="empty">后端启动后会显示模型供应商配置。</p>
      </div>
    </section>
  </main>
</template>
