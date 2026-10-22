<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { knowledgeBases, documents } from '../api'

const kbList = ref([])
const currentKbId = ref(null)
const docList = ref([])
const creating = ref(false)
const newKbName = ref('')
const newKbVisibility = ref('PRIVATE')
const uploading = ref(false)
const fileRef = ref()
const pollTimer = ref(null)
const visibilityVisible = ref(false)
const visibilityValue = ref('PRIVATE')
const membersVisible = ref(false)
const members = ref([])
const memberUsername = ref('')
const memberRole = ref('VIEWER')
const memberLoading = ref(false)

const currentKb = computed(
  () => kbList.value.find((kb) => kb.id === currentKbId.value) || null
)
const canWrite = computed(
  () => currentKb.value && currentKb.value.accessRole !== 'VIEWER'
)
const canManage = computed(
  () => ['OWNER', 'ADMIN'].includes(currentKb.value?.accessRole)
)

async function loadKbs() {
  kbList.value = await knowledgeBases.list()
  if (!currentKbId.value && kbList.value.length) {
    currentKbId.value = kbList.value[0].id
    await loadDocs()
  }
}

async function loadDocs() {
  if (!currentKbId.value) return
  docList.value = await documents.list(currentKbId.value)
}

async function createKb() {
  if (!newKbName.value) {
    ElMessage.warning('请输入知识库名称')
    return
  }
  await knowledgeBases.create({
    name: newKbName.value,
    visibility: newKbVisibility.value
  })
  creating.value = false
  newKbName.value = ''
  newKbVisibility.value = 'PRIVATE'
  await loadKbs()
}

function openVisibility() {
  visibilityValue.value = currentKb.value?.visibility || 'PRIVATE'
  visibilityVisible.value = true
}

async function saveVisibility() {
  try {
    await knowledgeBases.updateVisibility(currentKbId.value, visibilityValue.value)
    ElMessage.success('可见范围已更新')
    visibilityVisible.value = false
    await loadKbs()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function openMembers() {
  membersVisible.value = true
  await loadMembers()
}

async function loadMembers() {
  memberLoading.value = true
  try {
    members.value = await knowledgeBases.members(currentKbId.value)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    memberLoading.value = false
  }
}

async function grantMember() {
  if (!memberUsername.value.trim()) return ElMessage.warning('请输入成员用户名')
  try {
    await knowledgeBases.grantMember(currentKbId.value, {
      username: memberUsername.value.trim(),
      role: memberRole.value
    })
    memberUsername.value = ''
    ElMessage.success('成员权限已保存')
    await loadMembers()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function revokeMember(member) {
  try {
    await knowledgeBases.revokeMember(currentKbId.value, member.userId)
    ElMessage.success('已移除成员')
    await loadMembers()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function onFileChange(event) {
  const file = event.target.files[0]
  if (!file) return
  uploading.value = true
  try {
    await documents.upload(file, currentKbId.value)
    ElMessage.success('上传成功，开始处理，进度会自动刷新')
    await loadDocs()
    startPolling()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    uploading.value = false
    if (fileRef.value) fileRef.value.value = ''
  }
}

// 上传后每 3 秒自动刷新文档状态，全部处理完成（INDEXED/FAILED）后停止轮询
function startPolling() {
  stopPolling()
  pollTimer.value = setInterval(async () => {
    await loadDocs()
    const processing = docList.value.some(
      (doc) => doc.status === 'UPLOADED' || doc.status === 'INDEXING'
    )
    if (!processing) {
      stopPolling()
    }
  }, 3000)
}

function stopPolling() {
  if (pollTimer.value) {
    clearInterval(pollTimer.value)
    pollTimer.value = null
  }
}

// 按文件大小给粗略处理时长预估（仅供参考）
function estimateSeconds(fileSize) {
  const mb = fileSize / (1024 * 1024)
  if (mb <= 5) return '预计 30 秒内'
  if (mb <= 15) return '预计 1 分钟内'
  return '预计 1-3 分钟'
}

async function download(doc) {
  const data = await documents.presign(doc.id)
  window.open(data.url, '_blank')
}

onMounted(loadKbs)
onUnmounted(stopPolling)
</script>

<template>
  <div class="kb-view">
    <el-card>
      <div class="toolbar">
        <el-select v-model="currentKbId" placeholder="选择知识库" style="width: 240px" @change="loadDocs">
          <el-option v-for="kb in kbList" :key="kb.id" :label="kb.name" :value="kb.id" />
        </el-select>
        <el-tag v-if="currentKb" type="info">
          {{ currentKb.visibility }} · {{ currentKb.accessRole }}
        </el-tag>
        <el-button type="primary" @click="creating = true">新建知识库</el-button>
        <el-button v-if="canManage" @click="openVisibility">可见范围</el-button>
        <el-button v-if="canManage" @click="openMembers">成员</el-button>
        <el-button
          type="success"
          :loading="uploading"
          :disabled="!currentKbId || !canWrite"
        >
          <label class="upload-label">
            {{ canWrite ? '上传文档' : '只读知识库' }}
            <input ref="fileRef" type="file" accept=".pdf,.txt,.md,.docx" hidden @change="onFileChange" />
          </label>
        </el-button>
      </div>

      <el-dialog v-model="creating" title="新建知识库" width="420px">
        <el-input v-model="newKbName" placeholder="知识库名称" />
        <el-select v-model="newKbVisibility" style="width: 100%; margin-top: 12px">
          <el-option label="仅成员可见" value="PRIVATE" />
          <el-option label="同组织可读" value="ORGANIZATION" />
          <el-option label="同租户可读" value="TENANT" />
        </el-select>
        <template #footer>
          <el-button @click="creating = false">取消</el-button>
          <el-button type="primary" @click="createKb">创建</el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="visibilityVisible" title="知识库可见范围" width="420px">
        <el-radio-group v-model="visibilityValue">
          <el-radio value="PRIVATE">仅成员可见</el-radio>
          <el-radio value="ORGANIZATION">同组织可读</el-radio>
          <el-radio value="TENANT">同租户可读</el-radio>
        </el-radio-group>
        <p class="dialog-hint">可见范围只授予读取权限，写入仍需要 EDITOR 或 ADMIN。</p>
        <template #footer>
          <el-button @click="visibilityVisible = false">取消</el-button>
          <el-button type="primary" @click="saveVisibility">保存</el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="membersVisible" title="知识库成员" width="680px">
        <div class="member-form">
          <el-input v-model="memberUsername" placeholder="成员用户名" />
          <el-select v-model="memberRole" style="width: 130px">
            <el-option label="只读" value="VIEWER" />
            <el-option label="可写" value="EDITOR" />
            <el-option label="管理员" value="ADMIN" />
          </el-select>
          <el-button type="primary" @click="grantMember">添加/更新</el-button>
        </div>
        <el-table v-loading="memberLoading" :data="members" empty-text="暂无成员">
          <el-table-column prop="username" label="用户名" />
          <el-table-column prop="nickname" label="昵称" />
          <el-table-column prop="role" label="角色" width="100" />
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="danger" @click="revokeMember(row)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-dialog>

      <el-table :data="docList" style="margin-top: 16px" empty-text="暂无文档，请上传">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="fileName" label="文件名" />
        <el-table-column prop="fileType" label="类型" width="90" />
        <el-table-column prop="fileSize" label="大小" width="120">
          <template #default="{ row }">{{ (row.fileSize / 1024).toFixed(1) }} KB</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'INDEXED'" type="success">已入库</el-tag>
            <el-tag v-else-if="row.status === 'FAILED'" type="danger">失败</el-tag>
            <template v-else>
              <span class="spinner"></span>
              <span class="processing-text">处理中</span>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="预估" width="120">
          <template #default="{ row }">
            <span v-if="row.status !== 'INDEXED' && row.status !== 'FAILED'" class="estimate">
              {{ estimateSeconds(row.fileSize) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="primary" @click="download(row)">下载</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
}
.upload-label {
  cursor: pointer;
}
.spinner {
  display: inline-block;
  width: 12px;
  height: 12px;
  border: 2px solid #e0e0e0;
  border-top-color: #409eff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  vertical-align: middle;
  margin-right: 6px;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.processing-text {
  color: #e6a23c;
  font-size: 13px;
}
.estimate {
  color: #909399;
  font-size: 12px;
}
.dialog-hint {
  margin-top: 16px;
  color: #909399;
  font-size: 13px;
}
.member-form {
  display: grid;
  grid-template-columns: 1fr 130px auto;
  gap: 10px;
  margin-bottom: 14px;
}
</style>
