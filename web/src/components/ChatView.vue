<script setup>
import { ref } from 'vue'

const question = ref('')
const messages = ref([
  { role: 'assistant', content: '对话接口将在后续阶段接入。当前可先到"知识库"页上传文档并验证检索链路。' }
])
</script>

<template>
  <el-card class="chat-view">
    <div class="messages">
      <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
        <div class="bubble">{{ m.content }}</div>
      </div>
    </div>
    <div class="input-row">
      <el-input v-model="question" placeholder="输入问题（对话功能即将上线）" disabled />
      <el-button type="primary" disabled>发送</el-button>
    </div>
  </el-card>
</template>

<style scoped>
.chat-view {
  max-width: 860px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  height: calc(100vh - 140px);
}
.messages {
  flex: 1;
  overflow: auto;
  padding: 8px;
}
.msg {
  margin-bottom: 12px;
}
.bubble {
  display: inline-block;
  max-width: 80%;
  padding: 10px 14px;
  border-radius: 8px;
  background: #f4f4f5;
  line-height: 1.6;
}
.input-row {
  display: flex;
  gap: 12px;
  padding-top: 12px;
}
</style>
