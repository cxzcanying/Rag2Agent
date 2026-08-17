<script setup>
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { agent, knowledgeBases } from '../api'

const STORAGE_KEY = 'rag2agent_chat_messages'
const KB_STORAGE_KEY = 'rag2agent_chat_kb_id'

const kbId = ref(localStorage.getItem(KB_STORAGE_KEY) || '')
const kbList = ref([])
const question = ref('')
const sending = ref(false)
const messages = ref(loadMessages())
const pendingApproval = ref(null)
const processStage = ref('')

function loadMessages() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : []
  } catch {
    return []
  }
}

// 对话历史与所选知识库持久化到 localStorage，刷新不丢
watch(messages, (value) => localStorage.setItem(STORAGE_KEY, JSON.stringify(value)), { deep: true })
watch(kbId, (value) => localStorage.setItem(KB_STORAGE_KEY, value || ''))

onMounted(async () => {
  try {
    kbList.value = await knowledgeBases.list()
  } catch {
    // 列表加载失败时仍可手填，不阻塞
  }
})

async function send() {
  if (!kbId.value || !question.value.trim()) {
    ElMessage.warning('请输入知识库 ID 和问题')
    return
  }
  const q = question.value.trim()
  question.value = ''
  messages.value.push({ role: 'user', content: q })
  messages.value.push({ role: 'assistant', content: '', references: [] })
  // 关键：push 后从响应式数组取回的是 reactive 代理，后续 handleEvent 修改它才会触发渲染与持久化；
  // 如果在 push 前持有原始对象引用，修改会绕过 Vue 响应式，导致答案不刷新、localStorage 存旧值。
  const assistant = messages.value[messages.value.length - 1]
  sending.value = true
  pendingApproval.value = null
  processStage.value = '正在处理你的问题...'

  try {
    await agent.chat({ kbId: Number(kbId.value), query: q }, (event) => handleEvent(assistant, event))
  } catch (e) {
    assistant.content = '请求失败：' + e.message
  } finally {
    sending.value = false
    if (!processStage.value) {
      processStage.value = ''
    }
  }
}

function handleEvent(msg, event) {
  switch (event.type) {
    case 'reference':
      msg.references = event.data || []
      processStage.value =
        event.data && event.data.length ? '已找到相关资料，正在阅读...' : '正在检索知识库资料...'
      break
    case 'tool_start':
      processStage.value = '正在查阅更多资料...'
      break
    case 'approval_required':
      pendingApproval.value = event.data
      processStage.value = ''
      break
    case 'done':
      processStage.value = ''
      msg.content = event.data?.answer || ''
      msg.references = event.data?.references || msg.references
      break
    case 'error':
      processStage.value = ''
      msg.content = '执行出错：' + event.data
      break
  }
}

async function approve(approved) {
  if (!pendingApproval.value) return
  const approval = pendingApproval.value
  pendingApproval.value = null
  const assistant = messages.value[messages.value.length - 1]
  processStage.value = approved ? '操作已批准，正在继续处理...' : '操作已拒绝，正在继续处理...'
  try {
    const result = await agent.approve(approval.runId, approved)
    assistant.content = result.answer || (approved ? '操作已完成' : '操作已被拒绝')
    assistant.references = result.references || assistant.references
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    processStage.value = ''
  }
}
</script>

<template>
  <el-card class="chat-view">
    <div class="chat-toolbar">
      <el-select v-model="kbId" placeholder="选择知识库" style="width: 220px">
        <el-option v-for="kb in kbList" :key="kb.id" :label="kb.name" :value="kb.id" />
      </el-select>
    </div>

    <div class="messages">
      <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
        <div class="bubble">{{ m.content }}</div>

        <div v-if="m.references && m.references.length" class="references">
          <div class="ref-title">引用来源</div>
          <div v-for="(r, j) in m.references" :key="j" class="ref-item">
            <el-tag size="small">文档 {{ r.documentId }} / 块 {{ r.chunkIndex }}</el-tag>
            <span class="ref-content">{{ r.content }}</span>
          </div>
        </div>
      </div>

      <div v-if="pendingApproval" class="approval-card">
        <el-alert type="warning" :closable="false" show-icon>
          <template #title>
            模型请求执行高风险操作：<b>{{ pendingApproval.toolName }}</b>
          </template>
          <div class="approval-args">参数：{{ pendingApproval.arguments }}</div>
        </el-alert>
        <div class="approval-actions">
          <el-button type="danger" size="small" @click="approve(true)">批准</el-button>
          <el-button size="small" @click="approve(false)">拒绝</el-button>
        </div>
      </div>

      <div v-if="processStage" class="process-stage">
        <span class="spinner"></span>
        {{ processStage }}
      </div>
    </div>

    <div class="input-row">
      <el-input
        v-model="question"
        placeholder="输入问题，例如：Python 的数据类型有哪些？"
        :disabled="sending"
        @keyup.enter="send"
      />
      <el-button type="primary" :loading="sending" @click="send">发送</el-button>
    </div>
  </el-card>
</template>

<style scoped>
.chat-view {
  max-width: 900px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  height: calc(100vh - 140px);
}
.chat-toolbar {
  padding-bottom: 8px;
}
.messages {
  flex: 1;
  overflow: auto;
  padding: 8px;
}
.msg {
  margin-bottom: 12px;
}
.msg.user {
  text-align: right;
}
.bubble {
  display: inline-block;
  max-width: 80%;
  padding: 10px 14px;
  border-radius: 8px;
  background: #f4f4f5;
  line-height: 1.6;
  text-align: left;
  white-space: pre-wrap;
}
.msg.user .bubble {
  background: #d9ecff;
}
.references {
  margin-top: 8px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 8px;
  background: #fafafa;
  text-align: left;
}
.ref-title {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}
.ref-item {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin-bottom: 4px;
  font-size: 13px;
}
.ref-content {
  color: #606266;
  line-height: 1.5;
}
.approval-card {
  margin: 12px 0;
  text-align: left;
}
.approval-args {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}
.approval-actions {
  margin-top: 10px;
  display: flex;
  gap: 8px;
}
.process-stage {
  margin: 12px 0;
  padding: 10px 14px;
  background: #f5f7fa;
  border-radius: 8px;
  color: #606266;
  font-size: 14px;
  text-align: left;
}
.spinner {
  display: inline-block;
  width: 13px;
  height: 13px;
  border: 2px solid #e0e0e0;
  border-top-color: #409eff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  vertical-align: middle;
  margin-right: 8px;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.input-row {
  display: flex;
  gap: 12px;
  padding-top: 12px;
}
</style>
