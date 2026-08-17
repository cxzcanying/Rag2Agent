<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { agent } from '../api'

const kbId = ref('')
const question = ref('')
const sending = ref(false)
const messages = ref([])
const pendingApproval = ref(null)

async function send() {
  if (!kbId.value || !question.value.trim()) {
    ElMessage.warning('请输入知识库 ID 和问题')
    return
  }
  const q = question.value.trim()
  question.value = ''
  messages.value.push({ role: 'user', content: q })
  const assistant = { role: 'assistant', content: '', references: [], tools: [] }
  messages.value.push(assistant)
  sending.value = true
  pendingApproval.value = null

  try {
    await agent.chat({ kbId: Number(kbId.value), query: q }, (event) => handleEvent(assistant, event))
  } catch (e) {
    assistant.content = '请求失败：' + e.message
  } finally {
    sending.value = false
  }
}

function handleEvent(msg, event) {
  switch (event.type) {
    case 'reference':
      msg.references = event.data || []
      break
    case 'tool_start':
      msg.tools.push({ name: event.data?.name || '工具', status: '执行中' })
      break
    case 'approval_required':
      pendingApproval.value = event.data
      break
    case 'done':
      msg.content = event.data?.answer || ''
      msg.references = event.data?.references || msg.references
      break
    case 'error':
      msg.content = '执行出错：' + event.data
      break
  }
}

async function approve(approved) {
  if (!pendingApproval.value) return
  const approval = pendingApproval.value
  pendingApproval.value = null
  const assistant = messages.value[messages.value.length - 1]
  try {
    const result = await agent.approve(approval.runId, approved)
    assistant.content = result.answer || (approved ? '操作已完成' : '操作已被拒绝')
    assistant.references = result.references || assistant.references
  } catch (e) {
    ElMessage.error(e.message)
  }
}
</script>

<template>
  <el-card class="chat-view">
    <div class="chat-toolbar">
      <el-input v-model="kbId" placeholder="知识库 ID" style="width: 180px" />
    </div>

    <div class="messages">
      <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
        <div class="bubble">{{ m.content }}</div>

        <div v-if="m.tools && m.tools.length" class="tools">
          <el-tag v-for="(t, j) in m.tools" :key="j" size="small" type="warning">{{ t.name }}</el-tag>
        </div>

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
.tools {
  margin-top: 6px;
  display: flex;
  gap: 6px;
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
.input-row {
  display: flex;
  gap: 12px;
  padding-top: 12px;
}
</style>
