<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { knowledgeBases, documents } from '../api'

const kbList = ref([])
const currentKbId = ref(null)
const docList = ref([])
const creating = ref(false)
const newKbName = ref('')
const uploading = ref(false)
const fileRef = ref()

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
  await knowledgeBases.create({ name: newKbName.value })
  creating.value = false
  newKbName.value = ''
  await loadKbs()
}

async function onFileChange(event) {
  const file = event.target.files[0]
  if (!file) return
  uploading.value = true
  try {
    await documents.upload(file, currentKbId.value)
    ElMessage.success('上传成功，等待入库处理')
    await loadDocs()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    uploading.value = false
    if (fileRef.value) fileRef.value.value = ''
  }
}

async function download(doc) {
  const data = await documents.presign(doc.id)
  window.open(data.url, '_blank')
}

onMounted(loadKbs)
</script>

<template>
  <div class="kb-view">
    <el-card>
      <div class="toolbar">
        <el-select v-model="currentKbId" placeholder="选择知识库" style="width: 240px" @change="loadDocs">
          <el-option v-for="kb in kbList" :key="kb.id" :label="kb.name" :value="kb.id" />
        </el-select>
        <el-button type="primary" @click="creating = true">新建知识库</el-button>
        <el-button type="success" :loading="uploading" :disabled="!currentKbId">
          <label class="upload-label">
            上传文档
            <input ref="fileRef" type="file" accept=".pdf,.txt,.md,.docx" hidden @change="onFileChange" />
          </label>
        </el-button>
      </div>

      <el-dialog v-model="creating" title="新建知识库" width="420px">
        <el-input v-model="newKbName" placeholder="知识库名称" />
        <template #footer>
          <el-button @click="creating = false">取消</el-button>
          <el-button type="primary" @click="createKb">创建</el-button>
        </template>
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
            <el-tag :type="row.status === 'INDEXED' ? 'success' : 'info'">{{ row.status }}</el-tag>
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
</style>
