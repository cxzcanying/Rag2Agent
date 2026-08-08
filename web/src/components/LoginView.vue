<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { auth } from '../api'

const emit = defineEmits(['success'])

const mode = ref('login')
const loading = ref(false)
const form = ref({ username: '', password: '', nickname: '' })

async function submit() {
  if (!form.value.username || !form.value.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const data = mode.value === 'login'
      ? await auth.login({ username: form.value.username, password: form.value.password })
      : await auth.register(form.value)
    if (mode.value === 'register') {
      mode.value = 'login'
      ElMessage.success('注册成功，请登录')
      return
    }
    emit('success', data)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <h2>RAG2Agent</h2>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item v-if="mode === 'register'" label="昵称">
          <el-input v-model="form.nickname" placeholder="选填" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password placeholder="请输入密码" />
        </el-form-item>
        <el-button type="primary" :loading="loading" style="width: 100%" @click="submit">
          {{ mode === 'login' ? '登录' : '注册' }}
        </el-button>
        <div class="switch">
          <el-link type="primary" @click="mode = mode === 'login' ? 'register' : 'login'">
            {{ mode === 'login' ? '没有账号？去注册' : '已有账号？去登录' }}
          </el-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
}
.login-card {
  width: 360px;
}
.login-card h2 {
  text-align: center;
  margin-bottom: 16px;
}
.switch {
  margin-top: 12px;
  text-align: center;
}
</style>
