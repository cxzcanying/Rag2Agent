<script setup>
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { evaluations, knowledgeBases } from '../api'

const kbId = ref('')
const kbList = ref([])
const runName = ref('D13 baseline')
const strategy = ref('AUTO')
const topK = ref(5)
const candidateTopK = ref(20)
const rrfK = ref(60)
const rerankEnabled = ref(true)
const evaluateGeneration = ref(false)
const timeoutSeconds = ref(3600)
const caseJson = ref('[\n  {\n    "question": "示例问题",\n    "expectedAnswer": "",\n    "goldenDocumentIds": [1],\n    "caseType": "RAG",\n    "evidence": {}\n  }\n]')
const runs = ref([])
const coverage = ref(null)
const running = ref(false)
const importing = ref(false)
let pollTimer

async function loadKbs() {
  try {
    kbList.value = await knowledgeBases.list()
    if (!kbId.value && kbList.value.length) kbId.value = String(kbList.value[0].id)
    await Promise.all([loadRuns(), loadCoverage()])
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function loadRuns() {
  if (kbId.value) runs.value = await evaluations.listRuns(Number(kbId.value))
}

async function importCases() {
  if (!kbId.value) return ElMessage.warning('请选择知识库')
  try {
    const cases = JSON.parse(caseJson.value)
    if (!Array.isArray(cases)) throw new Error('评测用例必须是数组')
    importing.value = true
    const count = await evaluations.importCases({ kbId: Number(kbId.value), cases })
    ElMessage.success('已导入 ' + count + ' 条用例')
    await Promise.all([loadRuns(), loadCoverage()])
  } catch (e) {
    ElMessage.error(e.message || 'JSON 格式错误')
  } finally {
    importing.value = false
  }
}

async function loadCoverage() {
  if (!kbId.value) {
    coverage.value = null
    return
  }
  try {
    coverage.value = await evaluations.goldSetCoverage(Number(kbId.value))
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function runEvaluation() {
  if (!kbId.value) return ElMessage.warning('请选择知识库')
  running.value = true
  try {
    const submission = await evaluations.run({
      kbId: Number(kbId.value),
      name: runName.value,
      config: {
        strategy: strategy.value,
        topK: topK.value,
        candidateTopK: candidateTopK.value,
        rrfK: rrfK.value,
        rerankEnabled: rerankEnabled.value,
        evaluateGeneration: evaluateGeneration.value,
        timeoutSeconds: timeoutSeconds.value
      }
    }, crypto.randomUUID())
    ElMessage.success('评测已提交，运行 ID：' + submission.runId)
    await loadRuns()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    running.value = false
  }
}

async function cancelRun(run) {
  try {
    await evaluations.cancel(run.runId)
    ElMessage.success('已请求取消评测')
    await loadRuns()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function formatMetric(value) {
  return value == null ? '-' : (Number(value) * 100).toFixed(1) + '%'
}

watch(kbId, () => {
  loadRuns()
  loadCoverage()
})
onMounted(() => {
  loadKbs()
  pollTimer = window.setInterval(() => {
    if (runs.value.some((run) => run.status === 'QUEUED' || run.status === 'RUNNING')) loadRuns()
  }, 3000)
})
onUnmounted(() => window.clearInterval(pollTimer))
</script>

<template>
  <div class="evaluation-view">
    <el-form label-position="top" class="control-form">
      <el-form-item label="知识库">
        <el-select v-model="kbId" placeholder="选择知识库" style="width: 100%">
          <el-option v-for="kb in kbList" :key="kb.id" :label="kb.name" :value="String(kb.id)" />
        </el-select>
      </el-form-item>
      <el-form-item label="运行名称">
        <el-input v-model="runName" />
      </el-form-item>
      <el-form-item label="检索策略">
        <el-select v-model="strategy" style="width: 100%">
          <el-option label="自动路由" value="AUTO" />
          <el-option label="向量" value="VECTOR" />
          <el-option label="关键词" value="KEYWORD" />
          <el-option label="混合" value="HYBRID" />
        </el-select>
      </el-form-item>
      <el-form-item label="Top K">
        <el-input-number v-model="topK" :min="1" :max="100" />
      </el-form-item>
      <el-form-item label="候选数">
        <el-input-number v-model="candidateTopK" :min="5" :max="200" />
      </el-form-item>
      <el-form-item label="RRF k">
        <el-input-number v-model="rrfK" :min="0" :max="1000" />
      </el-form-item>
      <el-form-item label="选项">
        <el-checkbox v-model="rerankEnabled">启用 Rerank</el-checkbox>
        <el-checkbox v-model="evaluateGeneration">生成与裁判</el-checkbox>
      </el-form-item>
      <el-form-item label="超时（秒）">
        <el-input-number v-model="timeoutSeconds" :min="1" :max="86400" />
      </el-form-item>
      <div class="actions">
        <el-button type="primary" :loading="running" @click="runEvaluation">运行评测</el-button>
      </div>
    </el-form>

    <section class="dataset-section">
      <div class="section-heading">
        <h2>评测用例</h2>
        <el-button type="success" :loading="importing" @click="importCases">导入 JSON</el-button>
      </div>
      <div v-if="coverage" class="coverage">
        <div class="coverage-head">
          <strong>Gold Set 覆盖率</strong>
          <el-tag :type="coverage.valid ? 'success' : 'danger'">
            {{ coverage.valid ? '门禁通过' : '门禁未通过' }}
          </el-tag>
        </div>
        <div class="coverage-grid">
          <div v-for="(count, type) in coverage.caseCounts" :key="type" class="coverage-item">
            <span>{{ type }}</span>
            <strong>{{ count }}</strong>
          </div>
        </div>
        <el-alert
          v-if="coverage.missingCategories.length"
          type="warning"
          :closable="false"
          :title="'缺少类别：' + coverage.missingCategories.join('、')"
        />
        <el-alert
          v-for="(violation, index) in coverage.violations"
          :key="index"
          type="error"
          :closable="false"
          :title="violation.caseType + ' #' + violation.caseId + '：' + violation.message"
        />
      </div>
      <el-input v-model="caseJson" type="textarea" :rows="6" spellcheck="false" />
    </section>

    <section class="history-section">
      <div class="section-heading">
        <h2>运行历史</h2>
        <el-button text @click="loadRuns">刷新</el-button>
      </div>
      <el-table :data="runs" empty-text="暂无运行记录">
        <el-table-column prop="name" label="名称" min-width="150" />
        <el-table-column prop="status" label="状态" width="170" />
        <el-table-column label="进度" width="100">
          <template #default="{ row }">
            <el-progress :percentage="row.totalCases ? Math.round(row.completedCases * 100 / row.totalCases) : 0" :stroke-width="8" />
            <small>{{ row.completedCases }}/{{ row.totalCases }}</small>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button v-if="row.status === 'QUEUED' || row.status === 'RUNNING'" text type="danger" @click="cancelRun(row)">取消</el-button>
          </template>
        </el-table-column>
        <el-table-column label="Hit@k" width="100">
          <template #default="{ row }">{{ formatMetric(row.hitAtK) }}</template>
        </el-table-column>
        <el-table-column label="MRR" width="100">
          <template #default="{ row }">{{ formatMetric(row.mrr) }}</template>
        </el-table-column>
        <el-table-column label="Faithfulness" width="130">
          <template #default="{ row }">{{ formatMetric(row.faithfulness) }}</template>
        </el-table-column>
        <el-table-column label="Correctness" width="120">
          <template #default="{ row }">{{ formatMetric(row.answerCorrectness) }}</template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<style scoped>
.evaluation-view {
  max-width: 1120px;
  margin: 0 auto;
}
.control-form {
  display: grid;
  grid-template-columns: repeat(6, minmax(120px, 1fr));
  gap: 0 16px;
  padding: 20px;
  border: 1px solid #dbe3ef;
  border-radius: 8px;
  background: #fff;
}
.control-form .el-form-item:nth-child(1) {
  grid-column: span 2;
}
.control-form .el-form-item:nth-child(2) {
  grid-column: span 2;
}
.control-form .el-form-item:nth-child(7) {
  grid-column: span 2;
}
.actions {
  display: flex;
  align-items: end;
  padding-bottom: 18px;
}
.dataset-section,
.history-section {
  margin-top: 20px;
  padding: 20px;
  border: 1px solid #dbe3ef;
  border-radius: 8px;
  background: #fff;
}
.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.section-heading h2 {
  margin: 0;
  font-size: 18px;
}
.coverage {
  display: grid;
  gap: 10px;
  margin-bottom: 14px;
  padding: 14px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  background: #fafbfc;
}
.coverage-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.coverage-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
}
.coverage-item {
  display: flex;
  justify-content: space-between;
  padding: 8px 10px;
  border-radius: 6px;
  background: #fff;
  font-size: 13px;
}
@media (max-width: 800px) {
  .control-form {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .control-form .el-form-item,
  .control-form .el-form-item:nth-child(1),
  .control-form .el-form-item:nth-child(2),
  .control-form .el-form-item:nth-child(7) {
    grid-column: span 1;
  }
  .coverage-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
