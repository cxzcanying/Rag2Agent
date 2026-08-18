<script setup>
import { onMounted, ref } from 'vue'
import LoginView from './components/LoginView.vue'
import KbView from './components/KbView.vue'
import ChatView from './components/ChatView.vue'
import EvaluationView from './components/EvaluationView.vue'
import { auth } from './api'

const loggedIn = ref(false)
const currentTab = ref('kb')
const user = ref(null)

function onLoginSuccess(data) {
  localStorage.setItem('rag2agent_token', data.token)
  loggedIn.value = true
  user.value = data.user
}

function logout() {
  localStorage.removeItem('rag2agent_token')
  loggedIn.value = false
  user.value = null
}

onMounted(async () => {
  if (localStorage.getItem('rag2agent_token')) {
    try {
      user.value = await auth.me()
      loggedIn.value = true
    } catch {
      logout()
    }
  }
})
</script>

<template>
  <div class="app-shell">
    <template v-if="!loggedIn">
      <LoginView @success="onLoginSuccess" />
    </template>
    <template v-else>
      <header class="topbar">
        <h1>RAG2Agent</h1>
        <nav>
          <button :class="{ active: currentTab === 'kb' }" @click="currentTab = 'kb'">知识库</button>
          <button :class="{ active: currentTab === 'chat' }" @click="currentTab = 'chat'">对话</button>
          <button :class="{ active: currentTab === 'evaluation' }" @click="currentTab = 'evaluation'">评测</button>
        </nav>
        <div class="user-area">
          <span>{{ user?.nickname || user?.username }}</span>
          <el-button size="small" @click="logout">退出</el-button>
        </div>
      </header>
      <main class="main">
        <KbView v-if="currentTab === 'kb'" />
        <ChatView v-else-if="currentTab === 'chat'" />
        <EvaluationView v-else />
      </main>
    </template>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}
.topbar {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 0 24px;
  height: 56px;
  border-bottom: 1px solid #e5e7eb;
  background: #fff;
}
.topbar h1 {
  font-size: 18px;
  margin: 0;
}
.topbar nav {
  display: flex;
  gap: 8px;
  flex: 1;
}
.topbar nav button {
  border: none;
  background: transparent;
  padding: 6px 14px;
  cursor: pointer;
  border-radius: 6px;
  font-size: 14px;
}
.topbar nav button.active {
  background: #409eff;
  color: #fff;
}
.user-area {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 14px;
  color: #606266;
}
.main {
  flex: 1;
  padding: 24px;
  overflow: auto;
}
</style>
