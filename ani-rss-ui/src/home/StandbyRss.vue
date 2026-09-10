<template>
  <AniBT ref="aniBTRef" @callback="mikanCallback"/>
  <Mikan ref="mikanRef" @callback="mikanCallback"/>
  <AnimeGarden ref="animeGardenRef" @callback="mikanCallback"/>
  <el-dialog v-model="dialogVisible" center title="备用订阅">
    <el-alert v-if="!config.standbyRss" :closable="false"
              show-icon
              class="standby-alert" type="warning">
      <template #title>
        当前备用RSS功能并未开启, 可前往 <strong>设置-基本设置-RSS设置-备用RSS</strong> 启用
      </template>
    </el-alert>
    <div class="flex standby-toolbar">
      <div>
        <el-tooltip content="新增一行备用 RSS" placement="top">
          <el-button text bg icon="Plus" @click="plus" type="primary"/>
        </el-tooltip>
      </div>
      <div class="standby-spacer"></div>
      <div>
        <el-tooltip content="从 Mikan 选择字幕组" placement="top">
          <el-button @click="mikanShow" text bg>
            <template #icon>
              <img src="@/icon/icon-Mikan.png" alt="mikan" class="icon"/>
            </template>
            从 Mikan 添加
          </el-button>
        </el-tooltip>
      </div>
      <div class="standby-spacer"></div>
      <div>
        <el-tooltip content="从 AniBT 选择字幕组" placement="top">
          <el-button @click="aniBTShow" text bg>
            <template #icon>
              <img src="@/icon/icon-AniBT.png" alt="ani-bt" class="icon"/>
            </template>
            从 AniBT 添加
          </el-button>
        </el-tooltip>
      </div>
      <div class="standby-spacer"></div>
      <div>
        <el-tooltip content="从 AnimeGarden 选择字幕组" placement="top">
          <el-button bg text @click="animeGardenShow">
            <template #icon>
              <img src="@/icon/icon-AnimeGarden.png" alt="anime-garden" class="icon"/>
            </template>
            从 AnimeGarden 添加
          </el-button>
        </el-tooltip>
      </div>
    </div>
    <div>
      <el-table :data="standbyRss" height="400px" size="small">
        <el-table-column fixed label="字幕组" min-width="100px">
          <template #default="it">
            <div v-if="editIndex !== it.$index">
              {{ standbyRss[it.$index].label }}
            </div>
            <div v-else>
              <el-input v-model:model-value="standbyRss[it.$index].label" placeholder="未知字幕组"/>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="RSS" min-width="400px">
          <template #default="it">
            <div v-if="editIndex !== it.$index">
              <el-text line-clamp="1" size="small" truncated>
                {{ standbyRss[it.$index].url }}
              </el-text>
            </div>
            <div v-else>
              <el-input v-model:model-value="standbyRss[it.$index].url" placeholder="https://xxx.xxx" type="textarea"
                        size="small"
                        autosize/>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="偏移" width="150px">
          <template #default="it">
            <div v-if="editIndex !== it.$index">
              {{ standbyRss[it.$index].offset }}
            </div>
            <el-input-number v-else v-model:model-value="standbyRss[it.$index].offset" size="small"/>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300">
          <template #default="it">
            <div class="flex">
              <div>
                <el-tooltip :content="editIndex === it.$index ? '结束编辑' : '编辑这一行'" placement="top">
                  <el-button bg text icon="Edit" @click="editIndex = it.$index" v-if="editIndex !== it.$index"/>
                  <el-button bg text icon="Check" @click="check" type="primary" v-else/>
                </el-tooltip>
              </div>
              <div class="standby-action-spacer">
                <popconfirm title="删除这条备用 RSS?" @confirm="del(it.$index)">
                  <template #reference>
                    <el-button bg text icon="Delete" type="danger"/>
                  </template>
                </popconfirm>
              </div>
              <div class="standby-action-spacer">
                <el-tooltip content="上移" placement="top">
                  <el-button :disabled="it.$index < 1" bg icon="ArrowUpBold" text type="primary"
                             @click="move(it.$index,-1)"/>
                </el-tooltip>
              </div>
              <div class="standby-action-spacer">
                <el-tooltip content="下移" placement="top">
                  <el-button :disabled="it.$index >= standbyRss.length-1" bg icon="ArrowDownBold" text type="primary"
                             @click="move(it.$index,1)"/>
                </el-tooltip>
              </div>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>
    <div class="flex standby-footer">
      <el-text size="small" type="info" class="standby-footer-tip">
        此处改的是表单内容，点外层「确定」才会生效。
      </el-text>
      <span class="standby-spacer"></span>
      <el-tooltip :disabled="editIndex < 0" content="请先点 ✓ 结束当前行的编辑" placement="top">
        <span>
          <el-button icon="Check" bg text @click="ok" :disabled="editIndex > -1">确定</el-button>
        </span>
      </el-tooltip>
    </div>
  </el-dialog>
</template>

<script setup>
import {ref} from "vue";
import Mikan from "./Mikan.vue";
import AniBT from "@/home/AniBT.vue";
import {ElMessage} from "element-plus";
import * as http from "@/js/http.js";
import Popconfirm from "@/other/Popconfirm.vue";
import AnimeGarden from "@/home/AnimeGarden.vue";

const editIndex = ref(-1)

const dialogVisible = ref(false)
const standbyRss = ref()
const aniBTRef = ref()
const mikanRef = ref()
const animeGardenRef = ref()
const config = ref({
  standbyRss: true
})

let show = () => {
  editIndex.value = -1
  dialogVisible.value = true
  standbyRss.value = JSON.parse(JSON.stringify(props.ani.standbyRssList))

  http.config()
      .then(res => {
        config.value = res.data;
      })
}

let plus = () => {
  let object = {
    label: '未知字幕组',
    url: '',
    offset: props.ani.offset
  }
  standbyRss.value.push(object)
  editIndex.value = standbyRss.value.length - 1
  return object
}

let del = (index) => {
  editIndex.value = -1
  standbyRss.value = standbyRss.value.filter((s, i) => i !== index)
}

/**
 * 结束编辑：trim 后丢弃仍然为空 URL 的行，并明确告知丢了几行
 */
let check = () => {
  editIndex.value = -1
  let before = standbyRss.value.length
  standbyRss.value = standbyRss.value
      .map(it => {
        it.url = (it.url ?? '').trim()
        return it;
      })
      .filter(it => it.url !== '')

  let removed = before - standbyRss.value.length
  if (removed > 0) {
    ElMessage.warning(`已丢弃 ${removed} 行未填写 RSS 地址的空行`)
  }
}

/**
 * RSS 地址基本校验：必须能被解析成 http(s) URL
 */
let validateRss = () => {
  for (let it of standbyRss.value) {
    let url = (it.url ?? '').trim()
    if (!url) {
      ElMessage.error(`备用 RSS 中存在未填写的地址，请先删除该行；位置：${it.label || '未知字幕组'}`)
      return false
    }
    let ok = false
    try {
      let parsed = new URL(url)
      ok = parsed.protocol === 'http:' || parsed.protocol === 'https:'
    } catch (e) {
      ok = false
    }
    if (!ok) {
      ElMessage.error(`备用 RSS 地址格式不正确（需要 http/https）：${it.label || '未知字幕组'}`)
      return false
    }
  }
  return true
}

let ok = () => {
  check()
  if (!validateRss()) {
    return
  }
  props.ani.standbyRssList = standbyRss.value
  dialogVisible.value = false
  ElMessage.info('已应用，点外层「确定」后保存生效')
}

let move = (index, offset) => {
  let v = standbyRss.value[index]
  standbyRss.value[index] = standbyRss.value[index + offset]
  standbyRss.value[index + offset] = v
}

let mikanCallback = v => {
  let {subgroup, match, url} = v

  let later = plus()
  later.url = url
  later.label = subgroup

  let newMatch = JSON.parse(match).map(s => `{{${subgroup}}}:${s}`)

  // 剔除旧的同字幕组规则
  props.ani.match = props.ani.match.filter(it => it.indexOf(`{{${subgroup}}}:`) !== 0)

  props.ani.match.push(...newMatch)

  editIndex.value = -1
}

let animeGardenShow = () => {
  let bgmUrl = props.ani.bgmUrl;
  animeGardenRef.value?.show(bgmUrl)
}

let aniBTShow = () => {
  let bgmUrl = props.ani.bgmUrl;
  aniBTRef.value?.show(bgmUrl)
}

let mikanShow = () => {
  let query = props.ani.mikanTitle ? props.ani.mikanTitle : props.ani.title;

  if (props.ani.url) {
    // URL 非法时不能再抛异常（会表现为"点了没反应"）
    try {
      let url = new URL(props.ani.url);
      let mikanId = url.searchParams.get("bangumiId");
      if (mikanId) {
        query = `id: ${mikanId}`
      }
    } catch (e) {
      ElMessage.warning('当前主 RSS 地址格式不正确，已改为按标题搜索')
    }
  }

  mikanRef.value?.show(query)
}

defineExpose({show})
let props = defineProps(['ani'])

</script>

<style scoped>
.standby-alert {
  margin-bottom: 8px;
}

.standby-toolbar {
  width: 100%;
  flex-wrap: wrap;
}

.standby-spacer {
  margin: 3px;
}

.standby-action-spacer {
  margin-left: 4px;
}

.standby-footer {
  width: 100%;
  justify-content: flex-end;
  align-items: center;
  margin-top: 10px;
}

.standby-footer-tip {
  margin-right: auto;
}

.icon {
  width: 24px;
  height: 24px;
  border-radius: 8px;
}
</style>
