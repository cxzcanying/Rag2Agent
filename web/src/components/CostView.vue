<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { costs } from '../api'

const loading = ref(false)
const range = ref([])
const model = ref('')
const functionName = ref('')
const report = ref(null)

async function load() {
  loading.value = true
  try {
    const params = new URLSearchParams()
    if (range.value?.[0]) params.set('from', range.value[0])
    if (range.value?.[1]) params.set('to', range.value[1])
    if (model.value) params.set('model', model.value)
    if (functionName.value) params.set('function', functionName.value)
    report.value = await costs.summary(params)
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="cost-page">
    <div class="toolbar">
      <el-date-picker
        v-model="range"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
      />
      <el-input v-model="model" clearable placeholder="模型" />
      <el-input v-model="functionName" clearable placeholder="功能，例如 chat/evaluation" />
      <el-button type="primary" @click="load">查询</el-button>
    </div>

    <div class="summary">
      <el-statistic title="总费用" :value="Number(report?.totalCost || 0)" :precision="6" />
      <el-statistic title="请求数" :value="report?.daily?.reduce((sum, item) => sum + item.requests, 0) || 0" />
    </div>

    <el-table :data="report?.daily || []" stripe>
      <el-table-column prop="date" label="日期" width="120" />
      <el-table-column prop="requests" label="请求数" width="90" />
      <el-table-column prop="inputTokens" label="输入 Token" />
      <el-table-column prop="outputTokens" label="输出 Token" />
      <el-table-column label="总费用">
        <template #default="{ row }">{{ Number(row.totalCost).toFixed(6) }}</template>
      </el-table-column>
      <el-table-column label="缓存命中率">
        <template #default="{ row }">{{ (row.cacheHitRate * 100).toFixed(1) }}%</template>
      </el-table-column>
      <el-table-column label="高峰 / 空闲">
        <template #default="{ row }">
          {{ Number(row.peakCost).toFixed(6) }} / {{ Number(row.offPeakCost).toFixed(6) }}
        </template>
      </el-table-column>
    </el-table>

    <h3>任务费用</h3>
    <el-table :data="report?.tasks || []" stripe>
      <el-table-column prop="taskId" label="Task ID" width="120" />
      <el-table-column prop="runId" label="Run ID" width="120" />
      <el-table-column prop="function" label="功能" />
      <el-table-column prop="requests" label="请求数" width="100" />
      <el-table-column prop="totalTokens" label="Token" />
      <el-table-column label="费用">
        <template #default="{ row }">{{ Number(row.totalCost).toFixed(6) }}</template>
      </el-table-column>
    </el-table>

    <h3>运行费用</h3>
    <el-table :data="report?.runs || []" stripe>
      <el-table-column prop="runId" label="Run ID" width="120" />
      <el-table-column prop="requests" label="请求数" width="100" />
      <el-table-column label="跨天">
        <template #default="{ row }">{{ row.days > 1 ? `是（${row.days} 天）` : '否' }}</template>
      </el-table-column>
      <el-table-column prop="totalTokens" label="Token" />
      <el-table-column label="费用">
        <template #default="{ row }">{{ Number(row.totalCost).toFixed(6) }}</template>
      </el-table-column>
    </el-table>

    <h3>厂商余额对账</h3>
    <el-table :data="report?.balances || []">
      <el-table-column prop="provider" label="厂商" />
      <el-table-column prop="currency" label="币种" width="90" />
      <el-table-column prop="balance" label="余额" />
      <el-table-column prop="source" label="来源" />
      <el-table-column prop="checkedAt" label="核对时间" />
    </el-table>
  </section>
</template>

<style scoped>
.cost-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.toolbar {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.toolbar :deep(.el-input) {
  width: 220px;
}
.summary {
  display: flex;
  gap: 48px;
  padding: 18px;
  background: #fff;
  border-radius: 8px;
}
h3 {
  margin: 4px 0 0;
}
</style>
