// ==================== APP 命名空间 ====================
const App = {
  state: {
    currentTab: 'chat',
    currentSessionId: null,
    models: [],
    projects: [],
    chartInstance: null,
    mermaidInitialized: false,
    projectIndex: 0,
    qaHistory: [],
    totalChunks: 0,
    renderScheduled: false,
    pendingText: '',
    currentSources: [], // 对话当前的引用来源
    ingestStartTime: null, // 用于计算入库 ETA
    currentReIngestProject: null, // 当前重新入库的项目
  },

  utils: {
    escHtml(str) {
      if (!str) return '';
      const d = document.createElement('div');
      d.textContent = String(str);
      return d.innerHTML;
    },
    toast(msg, type = 'info', duration = 4000) {
      const container = document.getElementById('toastContainer');
      if (!container) return;
      const el = document.createElement('div');
      el.className = 'toast ' + type;
      el.innerHTML = '<span class="flex-1">' + App.utils.escHtml(msg) + '</span>';
      container.appendChild(el);
      // 双 rAF 确保 DOM 插入后再触发过渡
      requestAnimationFrame(() => requestAnimationFrame(() => el.classList.add('show')));
      setTimeout(() => {
        el.classList.remove('show');
        setTimeout(() => el.remove(), 350);
      }, duration);
    },
    async fetchWithRetry(url, options = {}, maxRetries = 2) {
      let lastError;
      for (let i = 0; i <= maxRetries; i++) {
        try { return await fetch(url, options); } 
        catch (err) { lastError = err; if (i < maxRetries) await new Promise(r => setTimeout(r, 1000 * Math.pow(2, i))); }
      }
      throw lastError;
    },
    genId() {
      if (crypto?.randomUUID) return crypto.randomUUID();
      return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
        const r = Math.random() * 16 | 0;
        return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
      });
    },
    copyToClipboard(text) {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(
          () => App.utils.toast('已复制', 'info', 1500),
          () => App.utils._fallbackCopy(text)
        );
      } else { App.utils._fallbackCopy(text); }
    },
    _fallbackCopy(text) {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.cssText = 'position:fixed;left:-9999px';
      document.body.appendChild(ta);
      ta.select();
      try { document.execCommand('copy'); App.utils.toast('已复制', 'info', 1500); }
      catch { App.utils.toast('复制失败', 'error'); }
      ta.remove();
    },
    showLoading(text) {
      const overlay = document.getElementById('loadingOverlay');
      const textEl = document.getElementById('loadingText');
      if (textEl) textEl.textContent = text || '处理中...';
      if (overlay) overlay.classList.add('active');
    },
    hideLoading() {
      const overlay = document.getElementById('loadingOverlay');
      if (overlay) overlay.classList.remove('active');
    }
  },

  switchTab(tabName) {
    ['chat', 'dashboard', 'ingestion', 'settings', 'evaluation'].forEach(id => {
      document.getElementById('tab-' + id)?.classList.add('hidden');
    });
    const target = document.getElementById('tab-' + tabName);
    if (target) {
      target.classList.remove('hidden');
      target.classList.add('animate-fade-in');
    }
    document.querySelectorAll('.tab-btn').forEach(btn => {
      btn.classList.remove('active', 'text-primary');
      btn.classList.add('text-gray-500');
    });
    document.querySelectorAll('.tab-btn[onclick*="' + tabName + '"]').forEach(btn => {
      btn.classList.add('active', 'text-primary');
      btn.classList.remove('text-gray-500');
    });
    App.state.currentTab = tabName;
    if (tabName === 'dashboard') App.dashboard.refresh();
    if (tabName === 'chat') { App.chat.loadModelList(); App.chat.loadSessions(); App.chat.loadStatusBar(); App.chat._updateStatusSession(); }
    if (tabName === 'ingestion') App.ingestion.loadProjects();
    if (tabName === 'settings') { App.settings.rag.load(); App.settings.llm.load(); App.settings.embed.load(); App.settings.rerank.load(); }
    if (tabName === 'evaluation') App.evaluation.init();
  },

  // ==================== 对话功能 ====================
  chat: {
    messageMap: new Map(),
    selectedMessageId: null,
    diagUserClosed: false,

    // ─── 工具名/图标映射 ───
    _toolNames: {
      searchKnowledge: '搜索知识库', readFile: '读取文件',
      listDirectory: '浏览目录', getKnowledgeBaseStats: '获取统计'
    },
    _toolIcons: {
      searchKnowledge: '🔍', readFile: '📄', listDirectory: '📁', getKnowledgeBaseStats: '📊'
    },

    // ─── 就绪面板 HTML ───
    _welcomeHTML: `<div id="chatWelcome" class="ready-panel animate-fade-in">
  <div class="ready-header">
    <div class="ready-icon">⚡</div>
    <div>
      <h2 class="ready-title">RAG 实验台就绪</h2>
      <p class="ready-subtitle">本地知识增强问答工作台 · 检索增强生成</p>
    </div>
  </div>
  <div class="ready-stats">
    <div class="ready-stat"><span class="ready-stat-label">模型</span><span id="readyLlm" class="ready-stat-val">—</span></div>
    <div class="ready-stat"><span class="ready-stat-label">知识库</span><span id="readyChunks" class="ready-stat-val">—</span></div>
    <div class="ready-stat"><span class="ready-stat-label">项目</span><span id="readyProjects" class="ready-stat-val">—</span></div>
    <div class="ready-stat"><span class="ready-stat-label">检索能力</span><span id="readyCapabilities" class="ready-cap-badges"><span class="cap-badge cap-off" id="capBM25">BM25</span><span class="cap-badge cap-off" id="capQR">QR</span><span class="cap-badge cap-off" id="capRerank">RERANK</span></span></div>
  </div>
  <div class="ready-actions">
    <button class="ready-action-btn" onclick="App.chat.quickQuestion('知识库中有哪些项目？')"><span class="ready-action-icon">📊</span><span>查看知识库状态</span></button>
    <button class="ready-action-btn" onclick="App.chat.quickQuestion('总结一下已入库的主要内容')"><span class="ready-action-icon">📋</span><span>总结已入库内容</span></button>
    <button class="ready-action-btn" onclick="App.switchTab('ingestion')"><span class="ready-action-icon">📁</span><span>去入库新项目</span></button>
    <button class="ready-action-btn" onclick="App.switchTab('settings')"><span class="ready-action-icon">⚙️</span><span>调整 RAG 配置</span></button>
  </div>
</div>`,
    _mermaidCounter: 0,

    // ─── 会话管理 ───
    async loadSessions() {
      try {
        const resp = await App.utils.fetchWithRetry('/api/sessions');
        if (!resp.ok) return;
        const sessions = await resp.json();
        const list = document.getElementById('sessionList');
        if (!list) return;
        if (sessions.length === 0) {
          list.innerHTML = '<div class="session-empty">暂无实验记录</div>';
          return;
        }
        list.innerHTML = sessions.map(s => {
          const isActive = s.id === App.state.currentSessionId;
          const time = new Date(s.updatedAt).toLocaleString('zh-CN', { month:'numeric', day:'numeric', hour:'2-digit', minute:'2-digit' });
          return `<div class="session-item ${isActive ? 'active' : ''}" onclick="App.chat.switchSession('${s.id}')" title="${App.utils.escHtml(s.title)}">
            <div class="session-title">${App.utils.escHtml(s.title)}</div>
            <div class="session-meta">
              <span class="session-time">${time}</span>
              <button onclick="event.stopPropagation();App.chat.deleteSession('${s.id}')" class="session-del" title="删除">✕</button>
            </div>
          </div>`;
        }).join('');
      } catch (e) { console.warn('加载会话列表失败', e); }
    },

    async switchSession(sessionId) {
      App.state.currentSessionId = sessionId;
      this._updateStatusSession();
      this.messageMap = new Map();
      this.selectedMessageId = null;
      const container = document.getElementById('chatContainer');
      container.innerHTML = '';
      try {
        const resp = await App.utils.fetchWithRetry(`/api/sessions/${sessionId}/messages`);
        if (!resp.ok) throw new Error('加载失败');
        const messages = await resp.json();
        for (const msg of messages) {
          if (msg.role === 'user') {
            const msgId = msg.id || App.utils.genId();
            this.messageMap.set(msgId, {
              id: msgId, role: 'user', content: msg.content,
              createdAt: new Date(msg.createdAt), sessionId,
              status: 'completed', sources: [], toolMetadata: []
            });
            container.insertAdjacentHTML('beforeend', `
              <div class="flex justify-end animate-slide-in mb-3">
                <div class="msg-user rounded-2xl px-5 py-3 max-w-[80%] shadow-sm">
                  <div class="text-sm leading-relaxed whitespace-pre-wrap">${App.utils.escHtml(msg.content)}</div>
                </div>
              </div>`);
          } else if (msg.role === 'assistant') {
            const msgId = msg.id || App.utils.genId();
            const domId = 'ai-msg-' + msgId;
            const obj = {
              id: msgId, role: 'assistant', content: msg.content,
              createdAt: new Date(msg.createdAt), sessionId,
              status: 'completed', sources: [], toolMetadata: [],
              domId, query: '', duration: null
            };
            this.messageMap.set(msgId, obj);
            this._renderAssistantCard(container, obj, false);
          }
        }
        const scrollEl = document.getElementById('chatScrollArea');
        scrollEl.scrollTo({ top: scrollEl.scrollHeight });
        this._updateStatusSession();
        this.loadSessions();
      } catch (e) {
        App.utils.toast('加载会话失败: ' + e.message, 'error');
      }
    },

    async deleteSession(sessionId) {
      if (!confirm('确定删除此会话？')) return;
      try {
        await App.utils.fetchWithRetry(`/api/sessions/${sessionId}`, { method: 'DELETE' });
        if (App.state.currentSessionId === sessionId) {
          this._resetChat();
        }
        this.loadSessions();
        App.utils.toast('会话已删除', 'info', 2000);
      } catch (e) { App.utils.toast('删除失败', 'error'); }
    },

    _resetChat() {
      App.state.currentSessionId = null;
      this.messageMap = new Map();
      this.selectedMessageId = null;
      this.diagUserClosed = false;
      document.getElementById('chatContainer').innerHTML = this._welcomeHTML;
      this._resetDiagPanel();
      this._updateStatusSession();
      this.loadSessions();
    },

    newChat() {
      this._resetChat();
    },

    clear() {
      this._resetChat();
      App.utils.toast('对话已清空', 'info', 2000);
    },

    _resetDiagPanel() {
      const el = document.getElementById('diagContent');
      if (el) el.innerHTML = '<div class="text-xs text-gray-400 text-center py-8">选中一条 AI 回复查看详情</div>';
      if (!this.diagUserClosed) this._collapseDiagPanel();
    },

    // ─── 模型列表 ───
    async loadModelList() {
      try {
        const resp = await App.utils.fetchWithRetry('/api/models');
        if (resp.ok) {
          const models = await resp.json();
          App.state.models = models;
          const select = document.getElementById('modelSelect');
          select.innerHTML = models.length > 0
            ? models.map(m => `<option value="${m.id}">${App.utils.escHtml(m.name)}</option>`).join('')
            : '<option value="">请先配置模型</option>';
          // 同步状态条模型名
          this._updateStatusLlm();
        }
      } catch (e) { document.getElementById('modelSelect').innerHTML = '<option value="">加载失败</option>'; }
    },

    _updateStatusLlm() {
      const sel = document.getElementById('modelSelect');
      const el = document.getElementById('statusLlm');
      if (sel && el) {
        const opt = sel.selectedOptions[0];
        el.textContent = opt ? opt.text : '未配置';
      }
    },

    onModelChange() { this._updateStatusLlm(); },

    quickQuestion(text) {
      const input = document.getElementById('chatInput');
      if (!input) return;
      input.value = text;
      input.style.height = 'auto';
      input.style.height = Math.min(input.scrollHeight, 200) + 'px';
      this.send();
    },

    onKeydown(e) {
      if (e.key === 'Enter' && !e.shiftKey) {
        if (e.isComposing) return;
        e.preventDefault();
        App.chat.send();
      }
    },

    // ─── 发送消息 ───
    async send() {
      const input = document.getElementById('chatInput');
      const query = input.value.trim();
      if (!query) return;

      const modelSelect = document.getElementById('modelSelect');
      const modelKey = modelSelect.value;
      if (!modelKey) return App.utils.toast('请先选择或配置模型', 'warning');

      input.value = '';
      input.style.height = 'auto';

      const modelName = modelSelect?.selectedOptions[0]?.text || '';
      const container = document.getElementById('chatContainer');
      const scrollEl = document.getElementById('chatScrollArea');

      document.getElementById('chatWelcome')?.remove();

      // 创建用户消息对象
      const userMsgId = App.utils.genId();
      this.messageMap.set(userMsgId, {
        id: userMsgId, role: 'user', content: query,
        createdAt: new Date(), sessionId: App.state.currentSessionId,
        status: 'completed', sources: [], toolMetadata: []
      });

      // 渲染用户气泡
      container.insertAdjacentHTML('beforeend', `
        <div class="flex justify-end animate-slide-in mb-3">
          <div class="msg-user rounded-2xl px-5 py-3 max-w-[80%] shadow-sm">
            <div class="text-sm leading-relaxed whitespace-pre-wrap">${App.utils.escHtml(query)}</div>
          </div>
        </div>`);

      // 创建 AI 消息对象
      App.state.currentSessionId = App.state.currentSessionId || App.utils.genId();
      this._updateStatusSession();
      const aiMsgId = App.utils.genId();
      const aiObj = {
        id: aiMsgId, role: 'assistant', content: '', query,
        createdAt: new Date(), modelName, sessionId: App.state.currentSessionId,
        status: 'streaming', sources: [], toolMetadata: [],
        startTime: Date.now(), endTime: null, duration: null,
        domId: 'ai-msg-' + aiMsgId
      };
      this.messageMap.set(aiMsgId, aiObj);
      // 软上限：清理最早消息防止内存无限增长
      if (this.messageMap.size > 200) {
        const firstKey = this.messageMap.keys().next().value;
        this.messageMap.delete(firstKey);
      }

      // 渲染 AI 卡片（流式模式）
      this._renderAssistantCard(container, aiObj, true);

      const responseDiv = document.querySelector(`#${aiObj.domId} .ai-response`);
      scrollEl.scrollTo({ top: scrollEl.scrollHeight, behavior: 'smooth' });

      document.getElementById('sendBtn').disabled = true;
      App.state.currentSources = [];

      let fullText = '';
      try {
        const resp = await App.utils.fetchWithRetry('/api/chat/stream', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ query, modelKey, sessionId: App.state.currentSessionId }),
        }, 1);

        if (!resp.ok) throw new Error('请求失败 HTTP ' + resp.status);

        const reader = resp.body.getReader();
        const decoder = new TextDecoder();

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          const chunk = decoder.decode(value, { stream: true });
          const lines = chunk.split('\n');
          for (const line of lines) {
            const t = line.trim();
            if (t.startsWith('data:') && t.length > 6) {
              try {
                const payload = JSON.parse(t.substring(5));
                if (payload.text) fullText += payload.text;
                if (payload.sources && payload.sources.length > 0) {
                  aiObj.sources = payload.sources;
                  App.state.currentSources = payload.sources;
                  this._renderCardCitations(aiObj);
                }
                if (payload.toolMetadata && payload.toolMetadata.length > 0) {
                  aiObj.toolMetadata.push(...payload.toolMetadata);
                  this._renderCardTools(aiObj);
                }
                this.scheduleRender(responseDiv, fullText);
              } catch (e) {
                fullText += t.substring(5).replace(/^"|"$/g, '').replace(/\\n/g, '\n');
                this.scheduleRender(responseDiv, fullText);
              }
              scrollEl.scrollTo({ top: scrollEl.scrollHeight });
            }
          }
        }
        aiObj.content = fullText;
        aiObj.status = 'completed';
      } catch (e) {
        aiObj.status = 'error';
        aiObj.content = fullText || '';
        // 保留已渲染的部分内容，在下方追加错误提示
        if (fullText) {
          responseDiv.insertAdjacentHTML('beforeend', `<p class="text-red-500 text-sm mt-2 border-t border-red-100 pt-2">✕ ${App.utils.escHtml(e.message)}</p>`);
        } else {
          responseDiv.innerHTML = `<p class="text-red-500 text-sm">✕ ${App.utils.escHtml(e.message)}</p>`;
        }
      } finally {
        aiObj.endTime = Date.now();
        aiObj.duration = aiObj.startTime ? aiObj.endTime - aiObj.startTime : null;
        document.getElementById('sendBtn').disabled = false;
        // 渲染元信息条
        this._renderCardMeta(aiObj);
        // 渲染反馈栏
        this._renderCardFeedback(aiObj, query, fullText);
        // 自动选中最新消息
        this.selectMessage(aiMsgId);
        scrollEl.scrollTo({ top: scrollEl.scrollHeight, behavior: 'smooth' });
        this.loadSessions();
      }
    },

    // ─── 结构化卡片渲染 ───
    _renderAssistantCard(container, obj, streaming) {
      const loadingHtml = streaming
        ? '<p class="text-gray-400 flex items-center gap-2"><span class="w-2 h-2 rounded-full bg-primary animate-ping"></span>思考并检索中...</p>'
        : '';
      container.insertAdjacentHTML('beforeend', `
        <div id="${obj.domId}" class="ai-card" data-msg-id="${obj.id}" onclick="App.chat.selectMessage('${obj.id}')">
          <div class="ai-section ai-tools collapsed" onclick="event.stopPropagation()">
            <div class="ai-section-hd" onclick="this.parentElement.classList.toggle('collapsed')">
              <span>🔧 工具调用 (<span class="tool-count">0</span>)</span>
              <svg class="chevron w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7"/></svg>
            </div>
            <div class="ai-section-bd"></div>
          </div>
          <div class="ai-response prose prose-sm w-full">${loadingHtml}</div>
          <div class="ai-meta-bar"></div>
          <div class="ai-section ai-cite" onclick="event.stopPropagation()">
            <div class="ai-section-hd" onclick="this.parentElement.classList.toggle('collapsed')">
              <span>📎 引用来源 (<span class="cite-count">0</span>)</span>
              <svg class="chevron w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7"/></svg>
            </div>
            <div class="ai-section-bd"></div>
          </div>
          <div class="feedback-slot"></div>
        </div>`);

      // 如果是历史消息（非流式），直接渲染内容
      if (!streaming && obj.content) {
        const responseDiv = document.querySelector(`#${obj.domId} .ai-response`);
        responseDiv.innerHTML = marked.parse(obj.content);
        responseDiv.querySelectorAll('pre code').forEach(block => {
          if (!block.classList.contains('language-mermaid') && window.hljs) hljs.highlightElement(block);
        });
        this.renderMermaid(responseDiv);
        // 渲染历史消息的元信息条（无耗时）
        this._renderCardMeta(obj);
      }
    },

    _renderCardTools(obj) {
      const card = document.getElementById(obj.domId);
      if (!card) return;
      const section = card.querySelector('.ai-tools');
      const bd = section.querySelector('.ai-section-bd');
      const count = card.querySelector('.tool-count');
      count.textContent = obj.toolMetadata.length;
      bd.innerHTML = obj.toolMetadata.map(t => {
        const icon = this._toolIcons[t.tool] || '⚙️';
        const name = this._toolNames[t.tool] || t.tool;
        return `<div class="tool-item"><span>${icon}</span><span class="tool-name">${name}</span><span class="tool-dur">${t.duration}ms</span></div>`;
      }).join('');
      if (obj.toolMetadata.length > 0) section.classList.remove('collapsed');
    },

    _renderCardCitations(obj) {
      const card = document.getElementById(obj.domId);
      if (!card) return;
      const section = card.querySelector('.ai-cite');
      const bd = section.querySelector('.ai-section-bd');
      const count = card.querySelector('.cite-count');
      count.textContent = obj.sources.length;
      bd.innerHTML = obj.sources.map((s, i) => `
        <div class="cite-item" title="${App.utils.escHtml(s.path || s.name)}">
          <span class="cite-idx">[${i + 1}]</span>
          <div class="cite-info">
            <span class="cite-name">${App.utils.escHtml(s.name)}</span>
            ${s.path ? '<span class="cite-path">' + App.utils.escHtml(s.path) + '</span>' : ''}
          </div>
        </div>`).join('');
    },

    _renderCardMeta(obj) {
      const card = document.getElementById(obj.domId);
      if (!card) return;
      const bar = card.querySelector('.ai-meta-bar');
      const badges = [];
      if (obj.sources.length > 0) badges.push(`<span class="meta-badge meta-cite">📎 引用 ${obj.sources.length}</span>`);
      if (obj.toolMetadata.length > 0) badges.push(`<span class="meta-badge meta-tool">🔧 工具 ${obj.toolMetadata.length}</span>`);
      if (obj.duration != null) badges.push(`<span class="meta-badge meta-dur">⏱ ${(obj.duration / 1000).toFixed(1)}s</span>`);
      if (obj.status === 'completed') badges.push('<span class="meta-badge meta-ok">✓ 完成</span>');
      else if (obj.status === 'error') badges.push('<span class="meta-badge meta-err">✕ 异常</span>');
      else if (obj.status === 'streaming') badges.push('<span class="meta-badge meta-stream">● 流式中</span>');
      bar.innerHTML = badges.join('');
    },

    _renderCardFeedback(obj, query, answer) {
      const card = document.getElementById(obj.domId);
      if (!card || !answer) return;
      const slot = card.querySelector('.feedback-slot');
      if (slot.querySelector('.feedback-bar')) return;
      const bar = document.createElement('div');
      bar.className = 'feedback-bar flex items-center gap-2 mt-2 pt-2 border-t border-gray-100';
      bar.dataset.query = query;
      bar.dataset.answer = answer.substring(0, 500);
      bar.innerHTML = `
        <span class="text-[11px] text-gray-400">有帮助？</span>
        <button onclick="App.chat.feedback(this, 1)" class="px-1.5 py-0.5 text-xs rounded hover:bg-green-50 text-gray-400 hover:text-green-600 transition-colors">👍</button>
        <button onclick="App.chat.feedback(this, -1)" class="px-1.5 py-0.5 text-xs rounded hover:bg-red-50 text-gray-400 hover:text-red-600 transition-colors">👎</button>`;
      slot.appendChild(bar);
    },

    // ─── scheduleRender（仅操作 .ai-response） ───
    scheduleRender(responseDiv, text) {
      App.state.pendingText = text;
      if (App.state.renderScheduled) return;
      App.state.renderScheduled = true;
      requestAnimationFrame(() => {
        try {
          if (responseDiv) {
            responseDiv.innerHTML = marked.parse(App.state.pendingText);
            App.chat.renderMermaid(responseDiv);
            responseDiv.querySelectorAll('pre code').forEach(block => {
              if (!block.classList.contains('language-mermaid') && window.hljs) hljs.highlightElement(block);
            });
          }
        } catch (e) {
          console.warn('Markdown 渲染异常:', e);
        }
        App.state.renderScheduled = false;
      });
    },

    renderMermaid(container) {
      container.querySelectorAll('pre code.language-mermaid').forEach((block, idx) => {
        const code = block.textContent.trim();
        if (!code) return;
        const wrapper = document.createElement('div');
        wrapper.className = 'my-4 flex justify-center mermaid-wrapper';
        const id = 'mmd-' + (++this._mermaidCounter);
        wrapper.id = id;
        block.parentNode.replaceWith(wrapper);
        if (window.mermaid) mermaid.render(id, code).then(({ svg }) => { wrapper.innerHTML = svg; }).catch(() => wrapper.innerHTML = '<div class="text-red-500 text-xs">图表渲染失败</div>');
      });
    },

    // ─── 诊断栏 ───
    selectMessage(msgId) {
      document.querySelectorAll('.ai-card.selected').forEach(el => el.classList.remove('selected'));
      const obj = this.messageMap.get(msgId);
      if (obj) {
        const card = document.getElementById(obj.domId);
        if (card) card.classList.add('selected');
      }
      this.selectedMessageId = msgId;
      // 自动展开诊断栏（除非用户手动关闭过）
      if (!this.diagUserClosed) this._expandDiagPanel();
      this.renderDiagPanel();
    },

    renderDiagPanel() {
      const el = document.getElementById('diagContent');
      if (!el) return;
      if (!this.selectedMessageId) { this._resetDiagPanel(); return; }
      const obj = this.messageMap.get(this.selectedMessageId);
      if (!obj) { this._resetDiagPanel(); return; }

      let html = '';

      // 请求快照
      html += `<div class="diag-section">
        <div class="diag-title">请求快照</div>
        <div class="diag-row"><span class="diag-label">问题</span><span class="diag-value" title="${App.utils.escHtml(obj.query || obj.content)}">${App.utils.escHtml((obj.query || obj.content || '').substring(0, 80))}</span></div>
        <div class="diag-row"><span class="diag-label">会话</span><span class="diag-value diag-mono" title="${App.utils.escHtml(obj.sessionId || '')}">${(obj.sessionId || '—').substring(0, 12)}...</span></div>
        <div class="diag-row"><span class="diag-label">模型</span><span class="diag-value">${App.utils.escHtml(obj.modelName || '—')}</span></div>
        <div class="diag-row"><span class="diag-label">时间</span><span class="diag-value">${obj.createdAt ? new Date(obj.createdAt).toLocaleString('zh-CN') : '—'}</span></div>
      </div>`;

      // 回答统计
      const charCount = (obj.content || '').length;
      html += `<div class="diag-section">
        <div class="diag-title">回答统计</div>
        <div class="diag-grid">
          <div class="diag-stat"><span class="diag-stat-val">${charCount}</span><span class="diag-stat-label">字符数</span></div>
          <div class="diag-stat"><span class="diag-stat-val">${obj.sources.length}</span><span class="diag-stat-label">引用数</span></div>
          <div class="diag-stat"><span class="diag-stat-val">${obj.toolMetadata.length}</span><span class="diag-stat-label">工具数</span></div>
          <div class="diag-stat"><span class="diag-stat-val">${obj.duration != null ? (obj.duration / 1000).toFixed(1) + 's' : '—'}</span><span class="diag-stat-label">耗时</span></div>
        </div>
      </div>`;

      // 引用来源详情
      if (obj.sources.length > 0) {
        html += `<div class="diag-section"><div class="diag-title">引用来源</div>`;
        obj.sources.forEach((s, i) => {
          html += `<div class="diag-cite-item">
            <span class="diag-cite-idx">[${i + 1}]</span>
            <div><div class="diag-cite-name">${App.utils.escHtml(s.name)}</div>
            ${s.path ? '<div class="diag-cite-path">' + App.utils.escHtml(s.path) + '</div>' : ''}</div>
          </div>`;
        });
        html += '</div>';
      } else if (obj.role === 'assistant') {
        html += '<div class="diag-section"><div class="diag-title">引用来源</div><div class="diag-empty">无引用数据</div></div>';
      }

      // 工具调用流水
      if (obj.toolMetadata.length > 0) {
        html += `<div class="diag-section"><div class="diag-title">工具调用流水</div>`;
        obj.toolMetadata.forEach(t => {
          const icon = this._toolIcons[t.tool] || '⚙️';
          const name = this._toolNames[t.tool] || t.tool;
          html += `<div class="diag-tool-row"><span>${icon} ${name}</span><span class="diag-tool-dur">${t.duration}ms</span></div>`;
        });
        html += '</div>';
      } else if (obj.role === 'assistant') {
        html += '<div class="diag-section"><div class="diag-title">工具调用流水</div><div class="diag-empty">无工具调用</div></div>';
      }

      // 诊断结论
      if (obj.role === 'assistant') {
        const conclusions = [];
        if (obj.status === 'streaming') {
          conclusions.push('检索与生成进行中...');
        } else {
          if (obj.sources.length >= 3) conclusions.push('本轮包含多个引用来源，证据较充分');
          else if (obj.sources.length > 0) conclusions.push('本轮引用来源较少，建议核实');
          else conclusions.push('本轮未提供引用来源，建议谨慎采信');
          if (obj.toolMetadata.length > 0) conclusions.push(`本轮触发了 ${obj.toolMetadata.length} 次工具调用，回答结合了外部数据`);
          else conclusions.push('本轮未触发工具调用');
          if (obj.status === 'error') conclusions.push('本轮回答异常终止');
        }

        html += `<div class="diag-section"><div class="diag-title">诊断结论</div><ul class="diag-conclusions">`;
        conclusions.forEach(c => { html += `<li>${c}</li>`; });
        html += '</ul></div>';
      }

      // 快速操作（使用 data 属性 + 事件委托，避免 XSS）
      const qText = App.utils.escHtml(obj.query || obj.content || '');
      const aText = App.utils.escHtml(obj.content || '');
      const sId = App.utils.escHtml(obj.sessionId || '');
      html += `<div class="diag-section"><div class="diag-title">快速操作</div>
        <div class="diag-actions">
          <button class="copy-btn" data-copy="${qText.replace(/"/g, '&quot;')}">复制问题</button>
          <button class="copy-btn" data-copy="${aText.replace(/"/g, '&quot;')}">复制回答</button>
          <button class="copy-btn" data-copy="${sId}">复制 Session</button>
        </div>
      </div>`;

      el.innerHTML = html;
    },

    toggleDiag() {
      const panel = document.getElementById('diagPanel');
      const btn = document.getElementById('diagToggleBtn');
      if (!panel) return;
      const isCollapsed = panel.classList.contains('diag-collapsed');
      if (isCollapsed) {
        panel.classList.remove('diag-collapsed');
        this.diagUserClosed = false;
      } else {
        panel.classList.add('diag-collapsed');
        this.diagUserClosed = true;
      }
      if (btn) btn.style.display = panel.classList.contains('diag-collapsed') ? '' : 'none';
    },

    _expandDiagPanel() {
      const panel = document.getElementById('diagPanel');
      const btn = document.getElementById('diagToggleBtn');
      if (panel) panel.classList.remove('diag-collapsed');
      if (btn) btn.style.display = 'none';
    },

    _collapseDiagPanel() {
      const panel = document.getElementById('diagPanel');
      const btn = document.getElementById('diagToggleBtn');
      if (panel) panel.classList.add('diag-collapsed');
      if (btn) btn.style.display = '';
    },

    _initDiagDelegation() {
      const el = document.getElementById('diagContent');
      if (!el || el._delegationInit) return;
      el._delegationInit = true;
      el.addEventListener('click', (e) => {
        const btn = e.target.closest('.copy-btn');
        if (btn) App.utils.copyToClipboard(btn.dataset.copy || '');
      });
    },

    toggleSessionCol() {
      const col = document.getElementById('sessCol');
      const expandBtn = document.getElementById('sessExpandBtn');
      if (!col) return;
      col.classList.toggle('collapsed');
      const collapsed = col.classList.contains('collapsed');
      localStorage.setItem('sessColCollapsed', collapsed ? '1' : '0');
      if (expandBtn) {
        expandBtn.classList.toggle('hidden', !collapsed);
        if (collapsed) this._positionExpandBtn();
      }
      const icon = document.getElementById('sessToggleIcon');
      if (icon) icon.style.transform = collapsed ? 'rotate(180deg)' : '';
    },

    _restoreSessionCol() {
      const col = document.getElementById('sessCol');
      const expandBtn = document.getElementById('sessExpandBtn');
      if (!col) return;
      if (localStorage.getItem('sessColCollapsed') === '1') {
        col.classList.add('collapsed');
        if (expandBtn) {
          expandBtn.classList.remove('hidden');
          setTimeout(() => this._positionExpandBtn(), 320);
        }
        const icon = document.getElementById('sessToggleIcon');
        if (icon) icon.style.transform = 'rotate(180deg)';
      }
    },

    _positionExpandBtn() {
      const expandBtn = document.getElementById('sessExpandBtn');
      const sidebar = document.getElementById('sidebar');
      if (!expandBtn || !sidebar || sidebar.offsetWidth === 0) return;
      const sbRect = sidebar.getBoundingClientRect();
      expandBtn.style.left = sbRect.right + 'px';
      expandBtn.style.top = (sbRect.top + sbRect.height / 2 - 22) + 'px';
    },

    // ─── 反馈 ───
    async feedback(btn, rating) {
      const bar = btn.closest('.feedback-bar');
      if (bar.querySelector('.fb-done')) return;
      const question = bar.dataset.query || '';
      const answer = bar.dataset.answer || '';
      try {
        const saveResp = await App.utils.fetchWithRetry('/api/chat/save', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ question, answer, modelName: document.getElementById('modelSelect')?.selectedOptions[0]?.text || '' })
        });
        const saveData = await saveResp.json();
        await App.utils.fetchWithRetry('/api/feedback', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ qaHistoryId: saveData.id, rating })
        });
        bar.innerHTML = `<span class="fb-done text-[11px] text-gray-400">${rating > 0 ? '感谢 👍' : '感谢 👎'}</span>`;
      } catch (e) {
        App.utils.toast('反馈提交失败', 'error');
      }
    },

    // ─── 状态条 ───
    async loadStatusBar() {
      // RAG 配置
      let bm25On = false, qrOn = false, rerankOn = false;
      try {
        const resp = await App.utils.fetchWithRetry('/api/configs');
        const configs = await resp.json();
        const getVal = (key) => configs.find(c => c.key === key)?.value;
        bm25On = getVal('enable_bm25') === 'true';
        qrOn = getVal('enable_query_rewrite') === 'true';
        rerankOn = getVal('enable_reranking') === 'true';
        this._renderStatusBadge('statusBM25', 'BM25', bm25On);
        this._renderStatusBadge('statusRerank', 'RERANK', rerankOn);
        this._renderStatusBadge('statusQR', 'QR', qrOn);
      } catch {
        ['statusBM25', 'statusRerank', 'statusQR'].forEach(id => {
          const el = document.getElementById(id);
          if (el) { el.textContent = el.textContent.split(':')[0] + ': 未提供'; el.className = 'status-badge off'; }
        });
      }
      // 就绪面板：检索能力 badge
      this._renderCapBadge('capBM25', bm25On);
      this._renderCapBadge('capQR', qrOn);
      this._renderCapBadge('capRerank', rerankOn);
      // 知识库统计
      let chunks = '—', projects = '—';
      try {
        const resp = await App.utils.fetchWithRetry('/api/dashboard/stats');
        const stats = await resp.json();
        chunks = stats.totalChunks ?? '—';
        projects = stats.projectCount ?? '—';
        document.getElementById('statusChunks').textContent = chunks;
        document.getElementById('statusProjects').textContent = projects;
      } catch {
        document.getElementById('statusChunks').textContent = '—';
        document.getElementById('statusProjects').textContent = '—';
      }
      // 就绪面板：统计值
      const rChunks = document.getElementById('readyChunks');
      const rProjects = document.getElementById('readyProjects');
      if (rChunks) rChunks.textContent = /^\d+$/.test(chunks) ? chunks + ' chunks' : chunks;
      if (rProjects) rProjects.textContent = projects;
      // Session ID
      const sessEl = document.getElementById('statusSession');
      if (sessEl) sessEl.textContent = App.state.currentSessionId ? App.state.currentSessionId.substring(0, 12) + '...' : '—';
      // 就绪面板：模型名
      const sel = document.getElementById('modelSelect');
      const rLlm = document.getElementById('readyLlm');
      if (sel && rLlm) {
        const opt = sel.selectedOptions[0];
        rLlm.textContent = opt ? opt.text : '未配置';
      }
    },

    _renderCapBadge(id, on) {
      const el = document.getElementById(id);
      if (!el) return;
      el.className = 'cap-badge ' + (on ? 'cap-on' : 'cap-off');
    },

    _renderStatusBadge(id, label, on) {
      const el = document.getElementById(id);
      if (!el) return;
      el.textContent = label + ': ' + (on ? 'ON' : 'OFF');
      el.className = 'status-badge ' + (on ? 'on' : 'off');
    },

    _updateStatusSession() {
      const el = document.getElementById('statusSession');
      if (el) el.textContent = App.state.currentSessionId ? App.state.currentSessionId.substring(0, 12) + '...' : '—';
    },
  },

  // ==================== 仪表盘功能 ====================
  dashboard: {
    refresh() {
      if (!App.state.mermaidInitialized) {
        try { if (window.mermaid) mermaid.run({ nodes: document.querySelectorAll('.mermaid:not([data-processed])') }); App.state.mermaidInitialized = true; } catch (e) {}
      }
      this.initECharts(); this.loadStats(); this.loadRecentQA(); this.loadEvalReport(); this.loadFeedbackStats();
    },
    async loadStats() {
      try {
        const resp = await App.utils.fetchWithRetry('/api/dashboard/stats');
        const stats = await resp.json();
        document.getElementById('statChunks').textContent = stats.totalChunks || 0;
        document.getElementById('statFiles').textContent = stats.fileCount || 0;
        document.getElementById('statProjects').textContent = stats.projectCount || 0;
        document.getElementById('statLastTime').textContent = stats.lastProcessTime || App.state.lastProcessTime || '--';
      } catch (e) {
        document.getElementById('statChunks').textContent = App.state.totalChunks || 0;
        document.getElementById('statProjects').textContent = App.ingestion.getProjects().length || 0;
        document.getElementById('statLastTime').textContent = App.state.lastProcessTime || '--';
      }
    },

    async loadRecentQA() {
      const container = document.getElementById('recentQA');
      try {
        const resp = await App.utils.fetchWithRetry('/api/dashboard/recent-qa');
        const records = await resp.json();
        if (records.length === 0) {
          container.innerHTML = '<div class="text-sm text-gray-400 text-center py-8">暂无记录</div>';
        } else {
          container.innerHTML = records.map(qa =>
            `<div class="p-3 bg-gray-50 rounded-lg border border-gray-100 animate-fade-in"><div class="text-sm font-medium text-gray-800 truncate">${App.utils.escHtml(qa.question)}</div><div class="text-xs text-gray-500 mt-1 line-clamp-2">${App.utils.escHtml(qa.answer || '')}</div><div class="text-xs text-gray-400 mt-1.5">${(qa.modelName || '')}${(qa.createdAt ? ' · ' + new Date(qa.createdAt).toLocaleString() : '')}</div></div>`
          ).join('');
        }
      } catch (e) {
        if (App.state.qaHistory.length === 0) {
          container.innerHTML = '<div class="text-sm text-gray-400 text-center py-8">暂无记录</div>';
        } else {
          container.innerHTML = App.state.qaHistory.slice(0, 10).map(qa =>
            `<div class="p-3 bg-gray-50 rounded-lg border border-gray-100 animate-fade-in"><div class="text-sm font-medium text-gray-800 truncate">${App.utils.escHtml(qa.question)}</div><div class="text-xs text-gray-500 mt-1 line-clamp-2">${App.utils.escHtml(qa.answer.substring(0, 120))}${(qa.answer.length > 120 ? '...' : '')}</div><div class="text-xs text-gray-400 mt-1.5">${qa.time}</div></div>`
          ).join('');
        }
      }
    },

    async loadEvalReport() {
      const container = document.getElementById('evalReport');
      if (!container) return;
      const card = container.closest('.bg-white');
      try {
        const resp = await App.utils.fetchWithRetry('/api/evaluation/report');
        const data = await resp.json();
        if (!data.found) {
          container.innerHTML = '<div class="text-sm text-gray-400 text-center py-6">暂无评估报告<br><span class="text-xs">点击进入评估中心创建测试集并执行</span></div>';
          if (card) { card.style.cursor = 'pointer'; card.onclick = () => App.switchTab('evaluation'); }
          return;
        }
        container.innerHTML = `
          <div class="grid grid-cols-2 gap-3 mb-3">
            <div class="text-center p-2 bg-blue-50 rounded-lg"><div class="text-lg font-bold text-blue-700">${(data.precisionAtK * 100).toFixed(1)}%</div><div class="text-xs text-gray-500">Precision@K</div></div>
            <div class="text-center p-2 bg-green-50 rounded-lg"><div class="text-lg font-bold text-green-700">${(data.hitRate * 100).toFixed(1)}%</div><div class="text-xs text-gray-500">Hit Rate</div></div>
            <div class="text-center p-2 bg-purple-50 rounded-lg"><div class="text-lg font-bold text-purple-700">${data.mrr ? data.mrr.toFixed(3) : '—'}</div><div class="text-xs text-gray-500">MRR</div></div>
            <div class="text-center p-2 bg-amber-50 rounded-lg"><div class="text-lg font-bold text-amber-700">${data.avgLatencyMs || '—'}ms</div><div class="text-xs text-gray-500">平均延迟</div></div>
          </div>
          <div class="text-xs text-gray-400 text-center">${data.completedCases}/${data.totalCases} 题 · ${data.evaluatedAt ? new Date(data.evaluatedAt).toLocaleString() : ''} · <span class="text-primary cursor-pointer hover:underline">查看详情 →</span></div>`;
        if (card) { card.style.cursor = 'pointer'; card.onclick = () => App.switchTab('evaluation'); }
      } catch (e) {
        container.innerHTML = '<div class="text-sm text-gray-400 text-center py-6">暂无评估报告<br><span class="text-xs">点击进入评估中心创建测试集并执行</span></div>';
        if (card) { card.style.cursor = 'pointer'; card.onclick = () => App.switchTab('evaluation'); }
      }
    },

    async loadFeedbackStats() {
      const container = document.getElementById('feedbackStats');
      if (!container) return;
      try {
        const resp = await App.utils.fetchWithRetry('/api/feedback/stats');
        const stats = await resp.json();
        if (stats.total === 0) {
          container.innerHTML = '<div class="text-sm text-gray-400 text-center py-4">暂无反馈</div>';
          return;
        }
        container.innerHTML = `
          <div class="flex items-center justify-between">
            <div class="text-center"><div class="text-xl font-bold text-green-600">${stats.positive}</div><div class="text-xs text-gray-500">👍</div></div>
            <div class="text-center"><div class="text-2xl font-bold text-primary">${stats.positiveRate}%</div><div class="text-xs text-gray-500">满意率</div></div>
            <div class="text-center"><div class="text-xl font-bold text-red-500">${stats.negative}</div><div class="text-xs text-gray-500">👎</div></div>
          </div>
          <div class="text-xs text-gray-400 text-center mt-2">共 ${stats.total} 条反馈</div>`;
      } catch (e) {
        container.innerHTML = '<div class="text-sm text-gray-400 text-center py-4">暂无反馈</div>';
      }
    },

    initECharts() {
      const dom = document.getElementById('chartContainer');
      if (!dom || !window.echarts) return;
      if (App.state.chartInstance) App.state.chartInstance.dispose();
      App.state.chartInstance = echarts.init(dom, null, { renderer: 'canvas' });

      App.utils.fetchWithRetry('/api/dashboard/language-stats')
        .then(r => r.json())
        .then(stats => {
          if (stats.length > 0) {
            App.state.chartInstance.setOption({
              backgroundColor: 'transparent',
              tooltip: { trigger: 'axis', backgroundColor: 'rgba(15,23,42,0.9)', borderColor: '#e5e7eb', textStyle: { color: '#f8fafc', fontSize: 12 } },
              grid: { left: 48, right: 24, top: 32, bottom: 48 },
              xAxis: { type: 'category', data: stats.map(s => s.language || 'unknown'), axisLabel: { color: '#6b7280', rotate: 30, fontSize: 11 }, axisLine: { lineStyle: { color: '#e5e7eb' } } },
              yAxis: { type: 'value', name: 'Chunks', nameTextStyle: { color: '#9ca3af' }, axisLabel: { color: '#6b7280' }, splitLine: { lineStyle: { color: '#f3f4f6' } } },
              series: [{ type: 'bar', data: stats.map(s => s.count), itemStyle: { color: '#10a37f', borderRadius: [4, 4, 0, 0] }, barWidth: '40%' }],
            });
          } else {
            App.dashboard.showEmptyChart(App.state.chartInstance);
          }
        })
        .catch(() => App.dashboard.showEmptyChart(App.state.chartInstance));
    },

    showEmptyChart(chart) {
      if (!chart) return;
      chart.setOption({
        backgroundColor: 'transparent',
        tooltip: { trigger: 'axis' },
        grid: { left: 48, right: 24, top: 32, bottom: 48 },
        xAxis: { type: 'category', data: ['Java', 'JavaScript', 'Markdown', 'Python', 'XML'], axisLabel: { color: '#9ca3af', rotate: 30, fontSize: 11 }, axisLine: { lineStyle: { color: '#e5e7eb' } } },
        yAxis: { type: 'value', name: 'Chunks', nameTextStyle: { color: '#9ca3af' }, axisLabel: { color: '#9ca3af' }, splitLine: { lineStyle: { color: '#f3f4f6' } } },
        series: [{ type: 'bar', data: [0, 0, 0, 0, 0], itemStyle: { color: '#e5e7eb', borderRadius: [4, 4, 0, 0] }, barWidth: '40%' }],
      });
    }
  },

  // ==================== 多步入库工作流 ====================
  ingestion: {
    /** 从后端加载已持久化的项目列表 */
    async loadProjects() {
      try {
        const resp = await fetch('/api/project-configs');
        if (!resp.ok) return;
        const projects = await resp.json();
        const container = document.getElementById('projectList');
        container.innerHTML = '';
        for (const p of projects) {
          // 获取 chunk 数量
          let chunkCount = 0;
          try {
            const countResp = await fetch(`/api/ingestion/chunks/${encodeURIComponent(p.name)}/count`);
            if (countResp.ok) {
              const data = await countResp.json();
              chunkCount = data.count || 0;
            }
          } catch (e) {}

          const idx = App.state.projectIndex++;
          const statusBadge = p.status === 'completed'
            ? '<span class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-700">已入库</span>'
            : '<span class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-yellow-100 text-yellow-700">待入库</span>';
          container.insertAdjacentHTML('beforeend', `
            <div id="proj-${idx}" class="project-item group flex flex-col bg-white border border-gray-200 p-3 rounded-lg shadow-sm" data-id="${p.id}" data-name="${App.utils.escHtml(p.name)}" data-path="${App.utils.escHtml(p.path)}">
              <div class="flex justify-between items-start">
                <div class="flex-1 min-w-0">
                  <div class="flex items-center gap-2">
                    <div class="text-sm font-semibold text-gray-800 truncate">${App.utils.escHtml(p.name)}</div>
                    ${statusBadge}
                  </div>
                  <div class="text-xs text-gray-500 font-mono mt-1 break-all">${App.utils.escHtml(p.path)}</div>
                  <div class="text-xs text-gray-400 mt-1">${chunkCount} chunks</div>
                </div>
              </div>
              <div class="flex gap-2 mt-2 pt-2 border-t border-gray-100">
                <button onclick="App.ingestion.viewProject('${p.id}')" class="flex-1 py-1.5 bg-gray-100 hover:bg-gray-200 text-gray-700 text-xs rounded-md transition-colors font-medium">详情</button>
                ${p.status === 'completed' ? `<button onclick="App.ingestion.reIngest('${p.id}', '${App.utils.escHtml(p.name)}', '${App.utils.escHtml(p.path)}')" class="flex-1 py-1.5 bg-primary hover:bg-primary-hover text-white text-xs rounded-md transition-colors font-medium">重新入库</button>` : ''}
                <button onclick="App.ingestion.deleteProject('${p.id}', '${App.utils.escHtml(p.name)}')" class="py-1.5 px-2 text-gray-400 hover:text-red-500 hover:bg-red-50 text-xs rounded-md transition-colors" title="删除">
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/></svg>
                </button>
              </div>
            </div>
          `);
        }
        // 空状态提示
        const hint = document.getElementById('emptyProjectHint');
        if (hint) hint.classList.toggle('hidden', projects.length > 0);

        // 入库按钮始终保持禁用，只有 addProject() 会临时启用
        const ingestBtn = document.getElementById('mainIngestBtn');
        if (ingestBtn) {
          ingestBtn.disabled = true;
          ingestBtn.textContent = '开始入库';
          ingestBtn.onclick = null;
        }
      } catch (e) {
        console.error('加载项目列表失败:', e);
      }
    },

    getProjects() {
      return Array.from(document.querySelectorAll('#projectList .project-item')).map(row => ({
        name: row.dataset.name, path: row.dataset.path
      }));
    },

    /** 添加项目（持久化到后端，同时触发右侧入库流程） */
    async addProject() {
      const nameEl = document.getElementById('projectName');
      const pathEl = document.getElementById('projectPath');
      const name = nameEl.value.trim();
      const path = pathEl.value.trim();

      if (!name) {
        nameEl.focus();
        nameEl.classList.add('border-red-400');
        setTimeout(() => nameEl.classList.remove('border-red-400'), 2000);
        return App.utils.toast('请输入项目名称', 'warning');
      }
      if (!path) {
        pathEl.focus();
        pathEl.classList.add('border-red-400');
        setTimeout(() => pathEl.classList.remove('border-red-400'), 2000);
        return App.utils.toast('请输入绝对路径', 'warning');
      }

      // 检测重复路径
      const existing = document.querySelectorAll('#projectList .project-item');
      for (const item of existing) {
        if (item.dataset.path === path) {
          return App.utils.toast('该路径已添加过，请勿重复添加', 'warning');
        }
      }

      try {
        const resp = await fetch('/api/project-configs', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ name, path })
        });
        if (!resp.ok) throw new Error('保存失败');
        App.utils.toast('项目已添加，准备入库...', 'success');
        nameEl.value = '';
        pathEl.value = '';

        // 不立即加载项目列表，等入库完成后再加载
        // 触发右侧入库流程
        this.startScanForProject(name, path);
      } catch (e) {
        App.utils.toast('添加失败: ' + e.message, 'error');
      }
    },

    /** 删除项目（同时清除 chunks） */
    async deleteProject(id, name) {
      if (!confirm(`确定删除项目 "${name}" 及其所有 chunks？`)) return;

      try {
        await fetch(`/api/ingestion/chunks/${encodeURIComponent(name)}`, { method: 'DELETE' });
        await fetch(`/api/project-configs/${id}`, { method: 'DELETE' });
        App.utils.toast(`项目 "${name}" 已删除`, 'success');
        this.loadProjects();
      } catch (e) {
        App.utils.toast('删除失败: ' + e.message, 'error');
      }
    },

    /** 查看项目详情 */
    async viewProject(id) {
      try {
        const resp = await fetch(`/api/project-configs/${id}`);
        if (!resp.ok) throw new Error('获取详情失败');
        const project = await resp.json();

        // 获取 chunk 数量
        let chunkCount = 0;
        try {
          const countResp = await fetch(`/api/ingestion/chunks/${encodeURIComponent(project.name)}/count`);
          if (countResp.ok) {
            const data = await countResp.json();
            chunkCount = data.count || 0;
          }
        } catch (e) {}

        const statusText = project.status === 'completed' ? '已入库' : '待入库';
        const statusColor = project.status === 'completed' ? 'text-green-600' : 'text-yellow-600';
        const ingestedTime = project.ingestedAt ? new Date(project.ingestedAt).toLocaleString() : '--';
        const createdTime = new Date(project.createdAt).toLocaleString();

        const modal = document.getElementById('projectDetailModal');
        document.getElementById('detailProjectName').textContent = project.name;
        document.getElementById('detailProjectPath').textContent = project.path;
        document.getElementById('detailProjectStatus').textContent = statusText;
        document.getElementById('detailProjectStatus').className = `font-medium ${statusColor}`;
        document.getElementById('detailProjectChunks').textContent = chunkCount;
        document.getElementById('detailProjectIngestedAt').textContent = ingestedTime;
        document.getElementById('detailProjectCreatedAt').textContent = createdTime;
        document.getElementById('detailProjectDescription').textContent = project.description || '暂无简介';

        modal.classList.remove('hidden');
        modal.style.display = 'flex';
      } catch (e) {
        App.utils.toast('获取详情失败: ' + e.message, 'error');
      }
    },

    /** 关闭项目详情模态框 */
    closeProjectDetail() {
      const modal = document.getElementById('projectDetailModal');
      modal.classList.add('hidden');
      modal.style.display = 'none';
    },
    async reIngest(id, name, path) {
      if (!confirm(`确定重新入库项目 "${name}"？将先清空旧数据再重新处理。`)) return;

      this.currentReIngestProject = { id, name, path };
      this.setStep(2);

      const btn = document.getElementById('mainIngestBtn');
      btn.disabled = true; btn.textContent = '扫描中...';

      try {
        const resp = await fetch('/api/ingestion/scan', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ projects: [{ name, path }] })
        });
        if (!resp.ok) throw new Error('扫描失败');
        const data = await resp.json();

        const exts = data.extensions || [];
        if (exts.length === 0) {
          App.utils.toast('未发现可处理的文件', 'warning');
          this.setStep(1);
          return;
        }

        const container = document.getElementById('fileTypeContainer');
        container.innerHTML = exts.map(e => `
          <label class="flex items-center gap-2 p-2.5 border border-gray-200 rounded-lg cursor-pointer hover:bg-gray-50 transition-colors bg-white shadow-sm">
            <input type="checkbox" value="${e.ext}" checked class="w-4 h-4 text-primary rounded border-gray-300 focus:ring-primary/20">
            <span class="text-sm font-medium text-gray-700">${e.ext} <span class="text-xs text-gray-400 font-normal">(${e.count} 文件)</span></span>
          </label>
        `).join('');

        btn.textContent = '确认入库';
        btn.disabled = false;
        btn.onclick = () => this.executeReIngest();
      } catch (e) {
        App.utils.toast('扫描失败: ' + e.message, 'error');
        this.setStep(1);
        btn.textContent = '开始入库';
        btn.disabled = true;
      }
    },

    /** 执行重新入库 */
    async executeReIngest() {
      const project = this.currentReIngestProject;
      if (!project) return;

      const checkedBoxes = document.querySelectorAll('#fileTypeContainer input:checked');
      if (checkedBoxes.length === 0) return App.utils.toast('请至少勾选一种文件类型', 'warning');
      const selectedExts = Array.from(checkedBoxes).map(cb => cb.value);

      this.setStep(3);
      document.getElementById('ingestLog').innerHTML = '';
      App.state.ingestStartTime = Date.now();

      this.addLog('info', `清空 "${project.name}" 的旧数据...`);
      try {
        await fetch(`/api/ingestion/chunks/${encodeURIComponent(project.name)}`, { method: 'DELETE' });
      } catch (e) {}

      this.addLog('info', `开始入库，所选类型: ${selectedExts.join(', ')}`);

      try {
        const resp = await fetch('/api/ingestion/process', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ projects: [{ name: project.name, path: project.path }], exts: selectedExts })
        });
        if (!resp.ok) throw new Error('入库请求失败');

        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop();

          for (const line of lines) {
            const t = line.trim();
            if (t.startsWith('data:')) {
              try {
                const data = JSON.parse(t.substring(5));
                this.addLog(data.status || 'info', data.message || '');

                if (data.current !== undefined && data.total !== undefined) {
                  const pct = data.progressPercentage || Math.floor((data.current / data.total) * 100);
                  const elapsed = (Date.now() - App.state.ingestStartTime) / 1000;
                  const speed = data.current / elapsed;
                  const remainTime = speed > 0 ? (data.total - data.current) / speed : 0;

                  document.getElementById('ingestBar').style.width = pct + '%';
                  document.getElementById('ingestPercent').textContent = pct + '%';
                  document.getElementById('ingestCount').textContent = `${data.current} / ${data.total}`;
                  document.getElementById('ingestETA').textContent = remainTime > 0 ? `${Math.floor(remainTime)}s` : '--';
                  if (data.currentFile) document.getElementById('ingestFile').textContent = data.currentFile;
                }

                if (data.status === 'done') {
                  document.getElementById('ingestStatus').textContent = '入库完成';
                  document.getElementById('ingestStatus').className = 'font-bold text-green-600';
                  App.utils.toast('入库完成！', 'success');
                  this.loadProjects();
                }
              } catch (e) {}
            }
          }
        }
      } catch(e) {
        this.addLog('error', e.message);
        App.utils.toast('入库失败: ' + e.message, 'error');
      } finally {
        const btn = document.getElementById('mainIngestBtn');
        btn.textContent = '开始入库';
        btn.disabled = true;
        btn.onclick = null;
        this.currentReIngestProject = null;
        this.loadProjects();
      }
    },

    /** 清空全部知识库 */
    async clearAllChunks() {
      const confirmText = prompt('此操作将清空所有 chunks 数据，且无法恢复。\n请输入"确认"继续：');
      if (confirmText !== '确认') return;

      try {
        const resp = await fetch('/api/ingestion/chunks', { method: 'DELETE' });
        if (!resp.ok) throw new Error('清空失败');
        const data = await resp.json();
        App.utils.toast(`已清空 ${data.deleted} 个 chunks`, 'success');
        this.loadProjects();
      } catch (e) {
        App.utils.toast('清空失败: ' + e.message, 'error');
      }
    },
    setStep(stepNum) {
      [1, 2, 3].forEach(i => {
        const dom = document.getElementById('step-' + (i===1?'scan':i===2?'select':'process'));
        const nav = document.getElementById('nav-step' + i);
        const circle = nav.querySelector('span');

        // 先重置
        dom.classList.add('opacity-0', 'pointer-events-none');
        nav.style.color = '';
        circle.style.backgroundColor = '';
        circle.innerHTML = i;

        if(i < stepNum) {
          // 已完成：绿色勾
          nav.style.color = '#16a34a';
          circle.style.backgroundColor = '#dcfce7';
          circle.innerHTML = '<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M5 13l4 4L19 7"/></svg>';
        } else if(i === stepNum) {
          // 当前步骤：蓝色高亮
          dom.classList.remove('opacity-0', 'pointer-events-none');
          nav.style.color = '#10a37f';
          circle.style.backgroundColor = '#d1fae5';
        }
        // else: 灰色（默认）
      });
    },
    async startScan() {
      // 此函数保留但不再直接使用，改为 startScanForProject
      App.utils.toast('请通过"添加项目"按钮添加新项目', 'info');
    },

    /** 扫描指定项目并触发右侧入库流程 */
    async startScanForProject(name, path) {
      this.currentReIngestProject = { name, path };
      this.setStep(2);

      const btn = document.getElementById('mainIngestBtn');
      btn.disabled = true; btn.textContent = '扫描中...';

      try {
        const resp = await fetch('/api/ingestion/scan', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ projects: [{ name, path }] })
        });
        if (!resp.ok) throw new Error('扫描失败');
        const data = await resp.json();

        const exts = data.extensions || [];
        if (exts.length === 0) {
          App.utils.toast('未发现可处理的文件', 'warning');
          this.setStep(1);
          btn.textContent = '开始入库'; btn.disabled = false;
          return;
        }

        const container = document.getElementById('fileTypeContainer');
        container.innerHTML = exts.map(e => `
          <label class="flex items-center gap-2 p-2.5 border border-gray-200 rounded-lg cursor-pointer hover:bg-gray-50 transition-colors bg-white shadow-sm">
            <input type="checkbox" value="${e.ext}" checked class="w-4 h-4 text-primary rounded border-gray-300 focus:ring-primary/20">
            <span class="text-sm font-medium text-gray-700">${e.ext} <span class="text-xs text-gray-400 font-normal">(${e.count} 文件)</span></span>
          </label>
        `).join('');

        btn.textContent = '确认入库'; btn.disabled = false;
        btn.onclick = () => this.executeReIngest();
      } catch (e) {
        App.utils.toast('扫描失败: ' + e.message, 'error');
        this.setStep(1);
        btn.textContent = '开始入库'; btn.disabled = true;
      }
    },
    async confirmTypesAndProcess() {
      const checkedBoxes = document.querySelectorAll('#fileTypeContainer input:checked');
      if(checkedBoxes.length === 0) return App.utils.toast('请至少勾选一种文件类型', 'warning');
      const selectedExts = Array.from(checkedBoxes).map(cb => cb.value);
      const projects = this.getProjects();

      this.setStep(3);
      document.getElementById('ingestLog').innerHTML = '';
      App.state.ingestStartTime = Date.now();

      // 入库前清空各项目的旧 chunks
      this.addLog('info', '清空旧数据...');
      for (const project of projects) {
        try {
          const resp = await fetch(`/api/ingestion/chunks/${encodeURIComponent(project.name)}`, { method: 'DELETE' });
          if (resp.ok) {
            const data = await resp.json();
            if (data.deleted > 0) this.addLog('info', `已清空 "${project.name}" 的 ${data.deleted} 个旧 chunks`);
          }
        } catch (e) {}
      }

      this.addLog('info', `开始入库，所选类型: ${selectedExts.join(', ')}`);

      try {
        const resp = await fetch('/api/ingestion/process', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ projects, exts: selectedExts })
        });
        if (!resp.ok) throw new Error('入库请求失败 HTTP ' + resp.status);

        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop();

          for (const line of lines) {
            const t = line.trim();
            if (t.startsWith('data:')) {
              try {
                const data = JSON.parse(t.substring(5));
                this.addLog(data.status || 'info', data.message || '');

                if (data.current !== undefined && data.total !== undefined) {
                  const pct = data.progressPercentage || Math.floor((data.current / data.total) * 100);
                  const elapsed = (Date.now() - App.state.ingestStartTime) / 1000;
                  const speed = data.current / elapsed;
                  const remainTime = speed > 0 ? (data.total - data.current) / speed : 0;

                  document.getElementById('ingestBar').style.width = pct + '%';
                  document.getElementById('ingestPercent').textContent = pct + '%';
                  document.getElementById('ingestCount').textContent = `${data.current} / ${data.total}`;
                  document.getElementById('ingestETA').textContent = remainTime > 0 ? `${Math.floor(remainTime)}s` : '--';
                  if (data.currentFile) document.getElementById('ingestFile').textContent = data.currentFile;
                }

                if (data.status === 'done') {
                  document.getElementById('ingestStatus').textContent = '入库完成';
                  document.getElementById('ingestStatus').className = 'font-bold text-green-600';
                  App.utils.toast('入库完成！', 'success');
                }
              } catch (e) {}
            }
          }
        }
      } catch(e) {
        this.addLog('error', e.message);
        App.utils.toast('入库失败: ' + e.message, 'error');
      }
    },
    addLog(type, msg) {
      const container = document.getElementById('ingestLog');
      const time = new Date().toLocaleTimeString('en-US', {hour12:false});
      const colors = { info:'text-blue-400', processing:'text-gray-300', error:'text-red-400', done:'text-green-400' };
      container.insertAdjacentHTML('beforeend', `<div class="${colors[type]}">[${time}] ${msg}</div>`);
      container.scrollTop = container.scrollHeight;
    }
  },

  // ==================== 评 估 ====================
  evaluation: {
    currentTestsetId: null,
    currentBatchId: null,
    pollTimer: null,
    trendChart: null,

    safeParse(json, fallback = []) {
      try { return JSON.parse(json || '[]'); } catch { return fallback; }
    },

    async init() {
      await this.loadTestsets();
      this.loadLatestReport();
      this.loadHistoryChart();
      if (!this._resizeRegistered) {
        window.addEventListener('resize', () => { if (this.trendChart) this.trendChart.resize(); });
        this._resizeRegistered = true;
      }
    },

    // ─── 测试集管理 ───
    async loadTestsets() {
      try {
        const resp = await App.utils.fetchWithRetry('/api/evaluation/testset');
        const testsets = await resp.json();
        const list = document.getElementById('testsetList');
        if (testsets.length === 0) {
          list.innerHTML = '<div class="text-sm text-gray-400 text-center py-6">暂无测试集</div>';
          return;
        }
        list.innerHTML = testsets.map(ts => `
          <div class="flex items-center gap-2 px-4 py-3 border-b border-gray-50 hover:bg-gray-50/50 cursor-pointer transition-colors ${ts.id === this.currentTestsetId ? 'bg-blue-50' : ''}"
               onclick="App.evaluation.selectTestset('${ts.id}', '${App.utils.escHtml(ts.name)}')">
            <div class="flex-1 min-w-0">
              <div class="text-sm font-medium text-gray-800 truncate">${App.utils.escHtml(ts.name)}</div>
              <div class="text-xs text-gray-400">${ts.caseCount} 条用例</div>
            </div>
            <div class="flex items-center gap-1 flex-shrink-0">
              <button onclick="event.stopPropagation();App.evaluation.exportTestset('${ts.id}')" class="text-gray-400 hover:text-blue-500 p-1 rounded" title="导出">
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"/></svg>
              </button>
              <button onclick="event.stopPropagation();App.evaluation.deleteTestset('${ts.id}','${App.utils.escHtml(ts.name)}')" class="text-gray-400 hover:text-red-500 p-1 rounded" title="删除">
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/></svg>
              </button>
            </div>
          </div>
        `).join('');
      } catch (e) { console.error('加载测试集失败:', e); }
    },

    async selectTestset(id, name) {
      this.currentTestsetId = id;
      document.getElementById('addCaseBtn').disabled = false;
      document.getElementById('runEvalBtn').disabled = false;
      await this.loadTestsets();
      await this.loadCases(id);
    },

    async deleteTestset(id, name) {
      if (!confirm('确定删除测试集 "' + name + '"？')) return;
      try {
        await fetch('/api/evaluation/testset/' + id, { method: 'DELETE' });
        if (this.currentTestsetId === id) {
          this.currentTestsetId = null;
          document.getElementById('addCaseBtn').disabled = true;
          document.getElementById('runEvalBtn').disabled = true;
          document.getElementById('caseList').innerHTML = '<div class="text-sm text-gray-400 text-center py-6">请先选择测试集</div>';
          document.getElementById('caseCount').textContent = '(0)';
        }
        this.loadTestsets();
        App.utils.toast('测试集已删除', 'success');
      } catch (e) { App.utils.toast('删除失败', 'error'); }
    },

    async exportTestset(id) {
      try {
        const resp = await fetch('/api/evaluation/testset/' + id + '/export');
        if (!resp.ok) { App.utils.toast('导出失败', 'error'); return; }
        const blob = await resp.blob();
        const disposition = resp.headers.get('Content-Disposition') || '';
        const match = disposition.match(/filename="?([^"]+)"?/);
        const filename = match ? match[1] : 'testset.json';
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url; a.download = filename;
        document.body.appendChild(a); a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        App.utils.toast('导出成功', 'success');
      } catch (e) { App.utils.toast('导出失败: ' + e.message, 'error'); }
    },

    triggerImport() {
      document.getElementById('importTestsetInput').click();
    },

    async handleImport(file) {
      if (!file) return;
      try {
        const text = await file.text();
        const data = JSON.parse(text);
        const resp = await fetch('/api/evaluation/testset/import', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(data)
        });
        const result = await resp.json();
        if (!resp.ok) { App.utils.toast(result.error || '导入失败', 'error'); return; }
        this.loadTestsets();
        App.utils.toast('导入成功：' + result.name + '，' + result.importedCases + ' 条用例', 'success');
      } catch (e) { App.utils.toast('导入失败: ' + e.message, 'error'); }
    },

    openCreateTestsetModal() {
      const name = prompt('输入测试集名称：');
      if (!name) return;
      const desc = prompt('输入描述（可选）：') || '';
      this.createTestset(name, desc);
    },

    async createTestset(name, description) {
      try {
        const resp = await fetch('/api/evaluation/testset', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ name, description: description || '' })
        });
        if (resp.ok) { this.loadTestsets(); App.utils.toast('创建成功', 'success'); }
      } catch (e) { App.utils.toast('创建失败', 'error'); }
    },

    // ─── 测试用例管理 ───
    async loadCases(testsetId) {
      try {
        const resp = await App.utils.fetchWithRetry('/api/evaluation/testset/' + testsetId + '/cases');
        const cases = await resp.json();
        document.getElementById('caseCount').textContent = '(' + cases.length + ')';
        const list = document.getElementById('caseList');
        if (cases.length === 0) {
          list.innerHTML = '<div class="text-sm text-gray-400 text-center py-6">暂无用例，点击"添加"创建</div>';
          return;
        }
        list.innerHTML = cases.map(c => {
          const files = this.safeParse(c.expectedFiles);
          const tags = this.safeParse(c.tags);
          return `<div class="px-4 py-3 border-b border-gray-50 hover:bg-gray-50/50 transition-colors">
            <div class="flex items-start justify-between gap-2">
              <div class="flex-1 min-w-0">
                <div class="text-sm text-gray-800">${App.utils.escHtml(c.question)}</div>
                <div class="text-xs text-gray-400 mt-1">期望文件：${files.map(f => App.utils.escHtml(f.split('/').pop())).join(', ')}</div>
                ${tags.length ? '<div class="flex gap-1 mt-1">' + tags.map(t => '<span class="text-[10px] px-1.5 py-0.5 bg-gray-100 text-gray-500 rounded">' + App.utils.escHtml(t) + '</span>').join('') + '</div>' : ''}
              </div>
              <button onclick="App.evaluation.deleteCase('${c.id}')" class="text-gray-400 hover:text-red-500 p-1 rounded flex-shrink-0" title="删除">
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/></svg>
              </button>
            </div>
          </div>`;
        }).join('');
      } catch (e) { console.error('加载用例失败:', e); }
    },

    openAddCaseModal() {
      if (!this.currentTestsetId) { App.utils.toast('请先选择测试集', 'warning'); return; }
      const question = prompt('输入测试问题：');
      if (!question) return;
      const files = prompt('期望命中的文件路径（逗号分隔）：');
      if (!files) return;
      const tags = prompt('标签（逗号分隔，可选）：') || '';
      this.addCase(question, files.split(',').map(f => f.trim()), tags.split(',').map(t => t.trim()).filter(Boolean));
    },

    async addCase(question, expectedFiles, tags) {
      try {
        await fetch('/api/evaluation/testset/' + this.currentTestsetId + '/cases', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ question, expectedFiles: JSON.stringify(expectedFiles), tags: JSON.stringify(tags) })
        });
        this.loadCases(this.currentTestsetId);
        this.loadTestsets();
        App.utils.toast('用例已添加', 'success');
      } catch (e) { App.utils.toast('添加失败', 'error'); }
    },

    async deleteCase(id) {
      if (!confirm('确定删除此用例？')) return;
      try {
        await fetch('/api/evaluation/testcase/' + id, { method: 'DELETE' });
        this.loadCases(this.currentTestsetId);
        this.loadTestsets();
        App.utils.toast('用例已删除', 'success');
      } catch (e) { App.utils.toast('删除失败', 'error'); }
    },

    // ─── 执行评估 ───
    async runEval() {
      if (!this.currentTestsetId) { App.utils.toast('请先选择测试集', 'warning'); return; }
      const runBtn = document.getElementById('runEvalBtn');
      if (runBtn.disabled) return;
      runBtn.disabled = true;
      runBtn.textContent = '评估中...';
      const k = parseInt(document.getElementById('evalK').value) || 5;
      try {
        const resp = await fetch('/api/evaluation/run', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ testsetId: this.currentTestsetId, k })
        });
        const data = await resp.json();
        if (data.error) { App.utils.toast(data.error, 'error'); runBtn.disabled = false; runBtn.textContent = '执行评估'; return; }
        this.currentBatchId = data.batchId;
        document.getElementById('evalProgress').classList.remove('hidden');
        document.getElementById('evalStatus').textContent = '评估中...';
        document.getElementById('evalProgressBar').style.width = '0%';
        this.startPolling(data.batchId);
      } catch (e) { App.utils.toast('启动评估失败: ' + e.message, 'error'); }
    },

    startPolling(batchId) {
      if (this.pollTimer) clearInterval(this.pollTimer);
      document.getElementById('cancelEvalBtn').classList.remove('hidden');
      this.pollTimer = setInterval(() => this.pollStatus(batchId), 2000);
    },

    resetRunBtn() {
      const btn = document.getElementById('runEvalBtn');
      if (btn) { btn.disabled = false; btn.textContent = '执行评估'; }
      const cancelBtn = document.getElementById('cancelEvalBtn');
      if (cancelBtn) cancelBtn.classList.add('hidden');
    },

    async cancelEval() {
      if (!this.currentBatchId) return;
      const cancelBtn = document.getElementById('cancelEvalBtn');
      if (cancelBtn) { cancelBtn.disabled = true; cancelBtn.textContent = '取消中...'; }
      try {
        const resp = await fetch('/api/evaluation/run/' + this.currentBatchId + '/cancel', { method: 'POST' });
        const data = await resp.json();
        if (data.error) { App.utils.toast(data.error, 'error'); }
      } catch (e) {
        App.utils.toast('取消失败: ' + e.message, 'error');
        if (cancelBtn) { cancelBtn.disabled = false; cancelBtn.textContent = '取消'; }
      }
    },

    async pollStatus(batchId) {
      try {
        const resp = await App.utils.fetchWithRetry('/api/evaluation/run/' + batchId + '/status');
        const data = await resp.json();
        document.getElementById('evalStatus').textContent = '评估中... ' + data.progress;
        document.getElementById('evalProgressText').textContent = data.progress;
        const pct = data.totalCases > 0 ? (data.completedCases / data.totalCases * 100) : 0;
        document.getElementById('evalProgressBar').style.width = pct + '%';

        if (data.status === 'completed') {
          clearInterval(this.pollTimer);
          document.getElementById('evalStatus').textContent = '评估完成';
          document.getElementById('evalProgressBar').style.width = '100%';
          if (data.result) this.renderReport(data.result);
          App.utils.toast('评估完成', 'success');
          this.resetRunBtn();
          this.loadHistoryChart();
        } else if (data.status === 'failed') {
          clearInterval(this.pollTimer);
          document.getElementById('evalStatus').textContent = '评估失败';
          App.utils.toast('评估失败: ' + (data.error || ''), 'error');
          this.resetRunBtn();
        } else if (data.status === 'cancelled') {
          clearInterval(this.pollTimer);
          document.getElementById('evalStatus').textContent = '评估已取消';
          App.utils.toast('评估已取消', 'warning');
          this.resetRunBtn();
          this.loadHistoryChart();
        }
      } catch (e) { console.error('轮询失败:', e); }
    },

    // ─── 报告展示 ───
    async loadLatestReport() {
      try {
        const resp = await App.utils.fetchWithRetry('/api/evaluation/report');
        const data = await resp.json();
        if (data.found) this.renderReport(data);
      } catch (e) {}
    },

    renderReport(report) {
      document.getElementById('metricP@K').textContent = report.precisionAtK != null ? (report.precisionAtK * 100).toFixed(1) + '%' : '—';
      document.getElementById('metricRecall').textContent = report.recall != null ? (report.recall * 100).toFixed(1) + '%' : '—';
      document.getElementById('metricMRR').textContent = report.mrr != null ? report.mrr.toFixed(3) : '—';
      document.getElementById('metricHitRate').textContent = report.hitRate != null ? (report.hitRate * 100).toFixed(1) + '%' : '—';

      const details = document.getElementById('evalDetails');
      if (!report.results || report.results.length === 0) {
        details.innerHTML = '<div class="text-sm text-gray-400 text-center py-12">暂无明细</div>';
        return;
      }
      details.innerHTML = `<table class="w-full text-xs">
        <thead class="bg-gray-50 text-gray-500 sticky top-0"><tr class="border-b border-gray-100">
          <th class="px-4 py-2 text-left font-medium">问题</th>
          <th class="px-4 py-2 text-center font-medium w-16">命中</th>
          <th class="px-4 py-2 text-center font-medium w-16">排名</th>
          <th class="px-4 py-2 text-center font-medium w-20">耗时</th>
          <th class="px-4 py-2 text-left font-medium">期望文件</th>
        </tr></thead>
        <tbody class="divide-y divide-gray-50">${report.results.map(r => {
          const expected = App.evaluation.safeParse(r.expectedFiles);
          const retrieved = App.evaluation.safeParse(r.retrievedFiles);
          return `<tr class="hover:bg-gray-50/50 ${r.parseWarning ? 'bg-red-50/30' : ''}">
            <td class="px-4 py-2.5 text-gray-800 max-w-[200px] truncate" title="${App.utils.escHtml(r.question)}">${App.utils.escHtml(r.question)}${r.parseWarning ? ' <span class="text-red-500 text-[10px]" title="' + App.utils.escHtml(r.parseWarning) + '">⚠</span>' : ''}</td>
            <td class="px-4 py-2.5 text-center">${r.hit ? '<span class="text-green-600 font-medium">✓</span>' : '<span class="text-red-400">✗</span>'}</td>
            <td class="px-4 py-2.5 text-center text-gray-600">${r.firstHitRank || '—'}</td>
            <td class="px-4 py-2.5 text-center text-gray-500">${r.latencyMs || '—'}ms</td>
            <td class="px-4 py-2.5 text-gray-500 max-w-[200px] truncate" title="${expected.map(f => App.utils.escHtml(f)).join(', ')}">${expected.map(f => App.utils.escHtml(f.split('/').pop())).join(', ')}</td>
          </tr>`;
        }).join('')}</tbody></table>`;
    },

    // ─── 历史趋势图 ───
    async loadHistoryChart() {
      const container = document.getElementById('evalHistoryChart');
      if (!container) return;
      try {
        const resp = await App.utils.fetchWithRetry('/api/evaluation/history');
        const data = await resp.json();
        if (!data || data.length === 0) {
          container.innerHTML = '<div class="text-sm text-gray-400 text-center py-12">暂无历史数据</div>';
          return;
        }
        // 按时间正序渲染
        const sorted = [...data].sort((a, b) => (a.evaluatedAt || '').localeCompare(b.evaluatedAt || ''));
        const times = sorted.map(b => {
          if (!b.evaluatedAt) return '—';
          const d = new Date(b.evaluatedAt);
          return (d.getMonth() + 1) + '/' + d.getDate() + ' ' + d.getHours() + ':' + String(d.getMinutes()).padStart(2, '0');
        });
        const series = [
          { name: 'Precision@K', data: sorted.map(b => b.precisionAtK != null ? +(b.precisionAtK * 100).toFixed(1) : null), color: '#2563eb' },
          { name: 'Recall', data: sorted.map(b => b.recall != null ? +(b.recall * 100).toFixed(1) : null), color: '#16a34a' },
          { name: 'MRR', data: sorted.map(b => b.mrr != null ? +b.mrr.toFixed(3) : null), color: '#9333ea' },
          { name: 'Hit Rate', data: sorted.map(b => b.hitRate != null ? +(b.hitRate * 100).toFixed(1) : null), color: '#d97706' },
        ];
        if (typeof echarts === 'undefined') {
          container.innerHTML = '<div class="text-sm text-gray-400 text-center py-12">ECharts 未加载</div>';
          return;
        }
        if (this.trendChart) { this.trendChart.dispose(); }
        const chart = echarts.init(container);
        this.trendChart = chart;
        chart.setOption({
          tooltip: { trigger: 'axis', formatter: params => {
            let tip = '<b>' + params[0].axisValue + '</b><br/>';
            params.forEach(p => { if (p.value != null) tip += p.marker + ' ' + p.seriesName + ': <b>' + p.value + (p.seriesName === 'MRR' ? '' : '%') + '</b><br/>'; });
            return tip;
          }},
          legend: { top: 10, textStyle: { fontSize: 11 } },
          grid: { left: 50, right: 20, top: 45, bottom: 30 },
          xAxis: { type: 'category', data: times, axisLabel: { fontSize: 10 } },
          yAxis: { type: 'value', axisLabel: { fontSize: 10, formatter: v => v + '%' }, min: 0 },
          series: series.map(s => ({
            name: s.name, type: 'line', data: s.data, smooth: true,
            lineStyle: { width: 2 }, itemStyle: { color: s.color },
            symbol: 'circle', symbolSize: 6
          }))
        });
      } catch (e) { console.error('加载历史趋势失败:', e); }
    },
  },

  // ==================== 设 置 ====================
  settings: {
    // RAG 配置
    rag: {
      async load() {
        const form = document.getElementById('ragConfig');
        if (!form) return;
        try {
          const [resp, rerankResp] = await Promise.all([
            fetch('/api/configs'),
            fetch('/api/reranking-configs').catch(() => ({ ok: false, json: () => [] }))
          ]);
          if (!resp.ok) { form.innerHTML = '<div class="text-red-500 text-sm p-4">加载失败</div>'; return; }
          const configs = await resp.json();
          const rerankConfigs = rerankResp.ok ? await rerankResp.json() : [];
          const hasRerankModels = rerankConfigs.length > 0;

          if (configs.length === 0) { form.innerHTML = '<div class="text-gray-400 text-sm p-4">暂无配置项</div>'; return; }
          form.innerHTML = configs.map(c => {
            const id = 'rag_' + c.key;
            const isRerankItem = ['reranking_model', 'reranking_top_n', 'reranking_pool_size'].includes(c.key);
            const disableRerank = isRerankItem && !hasRerankModels;
            let input;

            if (c.key === 'reranking_model') {
              // 下拉框：选项来自 Reranking 接入的模型
              const options = rerankConfigs.map(rc =>
                '<option value="' + App.utils.escHtml(rc.modelName) + '"' + (c.value === rc.modelName ? ' selected' : '') + '>' + App.utils.escHtml(rc.name) + ' (' + App.utils.escHtml(rc.modelName) + ')</option>'
              ).join('');
              input = '<select id="' + id + '" ' + (disableRerank ? 'disabled' : '') + ' class="border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-primary/20 focus:border-primary outline-none w-44 bg-white' + (disableRerank ? ' bg-gray-100 text-gray-400' : '') + '"><option value="">-- 请选择 --</option>' + options + '</select>';
            } else if (c.type === 'boolean') {
              const disabled = c.key === 'enable_reranking' && !hasRerankModels;
              input = '<select id="' + id + '" ' + (disabled ? 'disabled' : '') + ' class="border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-primary/20 focus:border-primary outline-none w-36 bg-white' + (disabled ? ' bg-gray-100 text-gray-400' : '') + '"><option value="true"' + (c.value === 'true' ? ' selected' : '') + '>开启</option><option value="false"' + (c.value === 'false' ? ' selected' : '') + '>关闭</option></select>';
            } else if (c.type === 'number') {
              input = '<input id="' + id + '" type="number" ' + (disableRerank ? 'disabled' : '') + ' class="border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-primary/20 focus:border-primary outline-none w-44 bg-white' + (disableRerank ? ' bg-gray-100 text-gray-400' : '') + '" value="' + App.utils.escHtml(c.value) + '" step="' + (c.value.includes('.') ? '0.01' : '1') + '" min="0.01">';
            } else if (c.type === 'textarea') {
              input = '<textarea id="' + id + '" rows="5" class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-primary/20 focus:border-primary outline-none bg-white font-mono">' + App.utils.escHtml(c.value) + '</textarea>';
            } else {
              input = '<input id="' + id + '" type="text" class="border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-primary/20 focus:border-primary outline-none w-44 bg-white" value="' + App.utils.escHtml(c.value) + '">';
            }
            if (c.type === 'textarea') {
              return '<div class="py-3 border-b border-gray-100 last:border-0"><div class="text-sm font-medium text-gray-700">' + App.utils.escHtml(c.key) + '</div><div class="text-xs text-gray-500 mt-0.5 mb-1">' + App.utils.escHtml(c.description || '') + '</div>' + input + '</div>';
            }
            return '<div class="flex items-center justify-between py-3 border-b border-gray-100 last:border-0"><div class="flex-1 pr-4"><div class="text-sm font-medium text-gray-700">' + App.utils.escHtml(c.key) + '</div><div class="text-xs text-gray-500 mt-0.5">' + App.utils.escHtml(c.description || '') + '</div></div>' + input + '</div>';
          }).join('');
        } catch (e) {
          form.innerHTML = '<div class="text-red-500 text-sm p-4">加载失败: ' + App.utils.escHtml(e.message) + '</div>';
        }
      },
      async save() {
        try {
          const resp0 = await fetch('/api/configs');
          if (!resp0.ok) { App.utils.toast('获取配置失败', 'error'); return; }
          const configs = await resp0.json();
          const updates = {};
          for (const c of configs) {
            const el = document.getElementById('rag_' + c.key);
            if (el) updates[c.key] = el.value;
          }
          const resp = await fetch('/api/configs', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(updates) });
          if (resp.ok) {
            const result = await resp.json();
            App.utils.toast('保存成功，已更新 ' + result.updated + ' 项配置', 'success');
            App.settings.rag.load();
          } else {
            App.utils.toast('保存失败: ' + resp.status, 'error');
          }
        } catch (e) {
          App.utils.toast('请求失败: ' + e.message, 'error');
        }
      },
    },

    // LLM 配置
    llm: {
      async load() {
        try {
          const resp = await App.utils.fetchWithRetry('/api/llm-configs');
          const configs = await resp.json();
          const tbody = document.getElementById('llmTable');
          if (configs.length === 0) {
            tbody.innerHTML = '<tr><td colspan="4" class="px-4 py-8 text-center text-gray-400 text-sm">暂无配置，点击"添加模型"创建</td></tr>';
            return;
          }
          tbody.innerHTML = configs.map(c =>
            `<tr class="border-b border-gray-100 hover:bg-gray-50/50 transition-colors">
              <td class="px-4 py-3 font-medium text-gray-800 text-sm">${App.utils.escHtml(c.name)}</td>
              <td class="px-4 py-3 text-sm text-gray-600">${App.utils.escHtml(c.modelName || '—')}</td>
              <td class="px-4 py-3">${c.isActive ? '<span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-50 text-green-700 border border-green-200">● 已激活</span>' : '<span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-500 border border-gray-200">○ 未激活</span>'}</td>
              <td class="px-4 py-3 text-center">
                <button onclick="App.settings.llm.toggleToolCalling('${c.id}', ${!c.enableToolCalling})" class="relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${c.enableToolCalling ? 'bg-primary' : 'bg-gray-300'}" title="${c.enableToolCalling ? 'Agent 已开启' : 'Agent 已关闭'}">
                  <span class="inline-block h-3.5 w-3.5 transform rounded-full bg-white transition-transform ${c.enableToolCalling ? 'translate-x-4.5' : 'translate-x-0.5'}" style="transform: translateX(${c.enableToolCalling ? '18px' : '2px'})"></span>
                </button>
              </td>
              <td class="px-4 py-2.5 text-right"><div class="flex gap-1.5 justify-end flex-wrap">
                <button class="px-2 py-1 text-xs border border-gray-200 text-gray-600 rounded hover:bg-gray-50 hover:border-gray-300 transition-colors" onclick="App.settings.llm.test('${c.id}', this)">测试</button>
                ${c.isActive
                  ? `<button class="px-2 py-1 text-xs border border-gray-200 text-gray-500 rounded hover:bg-gray-50 transition-colors" onclick="App.settings.llm.deactivate('${c.id}')">停用</button>`
                  : `<button class="px-2 py-1 text-xs border border-primary text-primary rounded hover:bg-green-50 transition-colors" onclick="App.settings.llm.activate('${c.id}')">激活</button>`}
                <button class="px-2 py-1 text-xs border border-gray-200 text-gray-600 rounded hover:bg-gray-50 transition-colors" onclick="App.settings.llm.edit('${c.id}')">编辑</button>
                <button class="px-2 py-1 text-xs border border-red-200 text-red-600 rounded hover:bg-red-50 transition-colors" onclick="App.settings.llm.delete('${c.id}','${App.utils.escHtml(c.name)}')">删除</button>
              </div></td></tr>`
          ).join('');
        } catch (e) {
          document.getElementById('llmTable').innerHTML = '<tr><td colspan="4" class="px-4 py-8 text-center text-red-500 text-sm">加载失败</td></tr>';
        }
      },

      openModal(config) {
        const modal = document.getElementById('llmModal');
        modal.classList.remove('hidden');
        modal.style.display = 'flex';
        document.getElementById('llmModalTitle').textContent = config ? '编辑 LLM 配置' : '添加 LLM 配置';
        document.getElementById('llmId').value = config ? config.id : '';
        document.getElementById('llmName').value = config ? config.name : '';
        document.getElementById('llmModel').value = config ? config.modelName : '';
        document.getElementById('llmType').value = config ? (config.apiFormat === 'anthropic_messages' ? 'anthropic' : 'openai') : 'openai';
        document.getElementById('llmUrl').value = config ? config.baseUrl : '';
        document.getElementById('llmKey').value = '';
        document.getElementById('llmKey').placeholder = config ? '留空则不修改' : 'sk-...';
      },

      closeModal() {
        const modal = document.getElementById('llmModal');
        modal.classList.add('hidden');
        modal.style.display = 'none';
      },

      async save(e) {
        e.preventDefault();
        const id = document.getElementById('llmId').value;
        const type = document.getElementById('llmType').value;
        const body = {
          name: document.getElementById('llmName').value.trim(),
          modelName: document.getElementById('llmModel').value.trim(),
          baseUrl: document.getElementById('llmUrl').value.trim(),
          apiKey: document.getElementById('llmKey').value,
          apiFormat: type === 'anthropic' ? 'anthropic_messages' : 'openai_chat_completions',
          isActive: false,
        };
        if (!body.name || !body.modelName || !body.baseUrl) { App.utils.toast('请填写必填字段', 'warning'); return; }
        try {
          const url = id ? '/api/llm-configs/' + id : '/api/llm-configs';
          const method = id ? 'PUT' : 'POST';
          const resp = await fetch(url, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
          if (resp.ok) {
            App.settings.llm.closeModal();
            App.settings.llm.load();
            App.utils.toast('保存成功', 'success');
          } else {
            const err = await resp.text();
            App.utils.toast('保存失败: ' + (err || resp.status), 'error');
          }
        } catch (err) {
          App.utils.toast('请求失败: ' + err.message, 'error');
        }
      },

      async edit(id) {
        try {
          const resp = await fetch('/api/llm-configs/' + id);
          if (resp.ok) App.settings.llm.openModal(await resp.json());
        } catch (e) { App.utils.toast('获取配置失败', 'error'); }
      },

      async delete(id, name) {
        if (!confirm('确定删除配置 "' + name + '"？')) return;
        try {
          await fetch('/api/llm-configs/' + id, { method: 'DELETE' });
          App.settings.llm.load();
          App.utils.toast('删除成功', 'success');
        } catch (e) { App.utils.toast('删除失败', 'error'); }
      },

      async activate(id) {
        try {
          const resp = await fetch('/api/llm-configs/' + id + '/activate', { method: 'POST' });
          if (resp.ok) App.settings.llm.load();
        } catch (e) { App.utils.toast('激活失败', 'error'); }
      },

      async deactivate(id) {
        try {
          const resp = await fetch('/api/llm-configs/' + id + '/deactivate', { method: 'POST' });
          if (resp.ok) App.settings.llm.load();
        } catch (e) { App.utils.toast('停用失败', 'error'); }
      },

      async test(id, btnEl) {
        const btn = btnEl;
        const orig = btn.textContent;
        btn.disabled = true;
        btn.textContent = '测试中...';
        try {
          const resp = await fetch('/api/llm-configs/' + id + '/test', { method: 'POST' });
          const result = await resp.json();
          if (result.success) {
            App.utils.toast(result.message + ' (' + result.responseTimeMs + 'ms)', 'success', 6000);
            App.settings.llm.load();
          } else {
            App.utils.toast('连接失败: ' + result.message, 'error', 6000);
          }
        } catch (e) { App.utils.toast('测试异常: ' + e.message, 'error'); }
        finally { btn.disabled = false; btn.textContent = orig; }
      },

      async toggleToolCalling(id, value) {
        try {
          const resp = await fetch('/api/llm-configs/' + id, {
            method: 'PUT', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ enableToolCalling: value })
          });
          if (resp.ok) App.settings.llm.load();
        } catch (e) { App.utils.toast('更新失败', 'error'); }
      },
    },

    // Embedding 配置
    embed: {
      async load() {
        try {
          const resp = await fetch('/api/embedding-configs');
          if (!resp.ok) return;
          const configs = await resp.json();
          const tbody = document.getElementById('embedTable');
          if (configs.length === 0) {
            tbody.innerHTML = '<tr><td colspan="4" class="px-4 py-8 text-center text-gray-400 text-sm">暂无配置，点击"新增向量"创建</td></tr>';
            return;
          }
          tbody.innerHTML = configs.map(c =>
            `<tr class="border-b border-gray-100 hover:bg-gray-50/50 transition-colors">
              <td class="px-4 py-3 font-medium text-gray-800 text-sm">${App.utils.escHtml(c.name)}</td>
              <td class="px-4 py-3 text-sm text-gray-600">${c.dimension || '—'}</td>
              <td class="px-4 py-3">${c.isActive ? '<span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-50 text-green-700 border border-green-200">● 已激活</span>' : '<span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-500 border border-gray-200">○ 未激活</span>'}</td>
              <td class="px-4 py-2.5 text-right"><div class="flex gap-1.5 justify-end flex-wrap">
                <button class="px-2 py-1 text-xs border border-gray-200 text-gray-600 rounded hover:bg-gray-50 hover:border-gray-300 transition-colors" onclick="App.settings.embed.test('${c.id}', this)">测试</button>
                ${c.isActive
                  ? `<button class="px-2 py-1 text-xs border border-gray-200 text-gray-500 rounded hover:bg-gray-50 transition-colors" onclick="App.settings.embed.deactivate('${c.id}')">停用</button>`
                  : `<button class="px-2 py-1 text-xs border border-primary text-primary rounded hover:bg-green-50 transition-colors" onclick="App.settings.embed.activate('${c.id}')">激活</button>`}
                <button class="px-2 py-1 text-xs border border-gray-200 text-gray-600 rounded hover:bg-gray-50 transition-colors" onclick="App.settings.embed.edit('${c.id}')">编辑</button>
                <button class="px-2 py-1 text-xs border border-red-200 text-red-600 rounded hover:bg-red-50 transition-colors" onclick="App.settings.embed.delete('${c.id}','${App.utils.escHtml(c.name)}')">删除</button>
              </div></td></tr>`
          ).join('');
        } catch (e) { console.error('加载 Embedding 配置失败:', e); }
      },

      openModal(config) {
        const modal = document.getElementById('embedModal');
        modal.classList.remove('hidden');
        modal.style.display = 'flex';
        document.getElementById('embedModalTitle').textContent = config ? '编辑 Embedding 配置' : '添加 Embedding 配置';
        document.getElementById('embedId').value = config ? config.id : '';
        document.getElementById('embedName').value = config ? config.name : '';
        document.getElementById('embedType').value = config ? config.provider : 'ollama';
        document.getElementById('embedModel').value = config ? config.modelName : '';
        document.getElementById('embedUrl').value = config ? config.baseUrl : 'http://localhost:11434';
        document.getElementById('embedKey').value = '';
        document.getElementById('embedKey').placeholder = config ? '留空则不修改' : '留空则使用 Ollama 本地';
        document.getElementById('embedDim').value = config ? config.dimension : 2560;
      },

      closeModal() {
        document.getElementById('embedModal').classList.add('hidden');
        document.getElementById('embedModal').style.display = 'none';
      },

      async save(e) {
        e.preventDefault();
        const id = document.getElementById('embedId').value;
        const body = {
          name: document.getElementById('embedName').value.trim(),
          provider: document.getElementById('embedType').value,
          baseUrl: document.getElementById('embedUrl').value.trim(),
          modelName: document.getElementById('embedModel').value.trim(),
          apiKey: document.getElementById('embedKey').value,
          dimension: parseInt(document.getElementById('embedDim').value) || 2560,
          isActive: false,
        };
        if (!body.name || !body.baseUrl || !body.modelName) { App.utils.toast('请填写必填字段', 'warning'); return; }
        try {
          const url = id ? '/api/embedding-configs/' + id : '/api/embedding-configs';
          const method = id ? 'PUT' : 'POST';
          const resp = await fetch(url, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
          if (resp.ok) {
            App.settings.embed.closeModal();
            App.settings.embed.load();
            App.utils.toast('保存成功', 'success');
          } else {
            App.utils.toast('保存失败: ' + resp.status, 'error');
          }
        } catch (err) { App.utils.toast('请求失败: ' + err.message, 'error'); }
      },

      async edit(id) {
        try {
          const resp = await fetch('/api/embedding-configs/' + id);
          if (resp.ok) App.settings.embed.openModal(await resp.json());
        } catch (e) { App.utils.toast('获取配置失败', 'error'); }
      },

      async delete(id, name) {
        if (!confirm('确定删除 Embedding 配置 "' + name + '"？')) return;
        try {
          await fetch('/api/embedding-configs/' + id, { method: 'DELETE' });
          App.settings.embed.load();
          App.utils.toast('删除成功', 'success');
        } catch (e) { App.utils.toast('删除失败', 'error'); }
      },

      async activate(id) {
        try {
          const r = await fetch('/api/embedding-configs/' + id + '/activate', { method: 'POST' });
          if (r.ok) App.settings.embed.load();
        } catch (e) { App.utils.toast('激活失败', 'error'); }
      },

      async deactivate(id) {
        try {
          const r = await fetch('/api/embedding-configs/' + id + '/deactivate', { method: 'POST' });
          if (r.ok) App.settings.embed.load();
        } catch (e) { App.utils.toast('停用失败', 'error'); }
      },

      async test(id, btnEl) {
        const btn = btnEl;
        const orig = btn.textContent;
        btn.disabled = true;
        btn.textContent = '测试中...';
        try {
          const resp = await fetch('/api/embedding-configs/' + id + '/test', { method: 'POST' });
          const result = await resp.json();
          if (result.success) {
            App.utils.toast('连接成功！' + result.message + ' (' + result.responseTimeMs + 'ms)', 'success');
          } else {
            App.utils.toast('连接失败: ' + result.message, 'error', 6000);
          }
        } catch (e) { App.utils.toast('测试异常: ' + e.message, 'error'); }
        finally { btn.disabled = false; btn.textContent = orig; }
      },
    },

    // Reranking 配置
    rerank: {
      async load() {
        try {
          const resp = await fetch('/api/reranking-configs');
          if (!resp.ok) return;
          const configs = await resp.json();
          const tbody = document.getElementById('rerankTable');
          const hint = document.getElementById('rerankDisabledHint');
          const hasActive = configs.some(c => c.isActive);

          if (configs.length === 0) {
            tbody.innerHTML = '<tr><td colspan="5" class="px-4 py-8 text-center text-gray-400 text-sm">暂无配置，点击"新增模型"创建</td></tr>';
            hint.classList.remove('hidden');
            App.settings.rerank.syncEnableReranking(false);
            App.settings.rerank.toggleRagRerankItems(false);
            return;
          }

          tbody.innerHTML = configs.map(c => {
            const providerLabel = c.provider === 'api' ? 'API' : 'Ollama';
            const providerBadge = c.provider === 'api'
              ? '<span class="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium bg-blue-50 text-blue-600 border border-blue-200">API</span>'
              : '<span class="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium bg-green-50 text-green-600 border border-green-200">Ollama</span>';
            return `<tr class="border-b border-gray-100 hover:bg-gray-50/50 transition-colors">
              <td class="px-4 py-3 font-medium text-gray-800 text-sm">${App.utils.escHtml(c.name)}</td>
              <td class="px-4 py-3">${providerBadge}</td>
              <td class="px-4 py-3 text-sm text-gray-600">${App.utils.escHtml(c.modelName || '—')}</td>
              <td class="px-4 py-3">${c.isActive ? '<span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-50 text-green-700 border border-green-200">● 已激活</span>' : '<span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-500 border border-gray-200">○ 未激活</span>'}</td>
              <td class="px-4 py-2.5 text-right"><div class="flex gap-1.5 justify-end flex-wrap">
                <button class="px-2 py-1 text-xs border border-gray-200 text-gray-600 rounded hover:bg-gray-50 hover:border-gray-300 transition-colors" onclick="App.settings.rerank.test('${c.id}', this)">测试</button>
                ${c.isActive
                  ? `<button class="px-2 py-1 text-xs border border-gray-200 text-gray-500 rounded hover:bg-gray-50 transition-colors" onclick="App.settings.rerank.deactivate('${c.id}')">停用</button>`
                  : `<button class="px-2 py-1 text-xs border border-primary text-primary rounded hover:bg-green-50 transition-colors" onclick="App.settings.rerank.activate('${c.id}')">激活</button>`}
                <button class="px-2 py-1 text-xs border border-gray-200 text-gray-600 rounded hover:bg-gray-50 transition-colors" onclick="App.settings.rerank.edit('${c.id}')">编辑</button>
                <button class="px-2 py-1 text-xs border border-red-200 text-red-600 rounded hover:bg-red-50 transition-colors" onclick="App.settings.rerank.delete('${c.id}','${App.utils.escHtml(c.name)}')">删除</button>
              </div></td></tr>`;
          }).join('');

          hint.classList.toggle('hidden', hasActive);
          App.settings.rerank.syncEnableReranking(hasActive);
          App.settings.rerank.toggleRagRerankItems(hasActive);
        } catch (e) { console.error('加载 Reranking 配置失败:', e); }
      },

      syncEnableReranking(hasActive) {
        const keys = ['enable_reranking', 'reranking_model', 'reranking_top_n', 'reranking_pool_size'];
        keys.forEach(key => {
          const el = document.getElementById('rag_' + key);
          if (!el) return;
          if (!hasActive) {
            if (key === 'enable_reranking' || key === 'reranking_model') el.value = key === 'enable_reranking' ? 'false' : '';
            el.disabled = true;
            el.classList.add('bg-gray-100', 'text-gray-400');
          } else {
            el.disabled = false;
            el.classList.remove('bg-gray-100', 'text-gray-400');
          }
        });
        if (hasActive) {
          fetch('/api/reranking-configs').then(r => r.json()).then(configs => {
            const active = configs.find(c => c.isActive);
            const modelEl = document.getElementById('rag_reranking_model');
            if (active && modelEl) modelEl.value = active.modelName;
          }).catch(() => {});
        }
      },

      toggleRagRerankItems(hasActive) {
        // 已由 syncEnableReranking 统一处理 disable/enable，此方法保留空实现
      },

      onProviderChange() {
        const provider = document.getElementById('rerankProvider').value;
        const keyRow = document.getElementById('rerankKeyRow');
        const urlLabel = document.getElementById('rerankUrlLabel');
        const urlInput = document.getElementById('rerankUrl');
        if (provider === 'api') {
          keyRow.classList.remove('hidden');
          urlLabel.textContent = 'API Endpoint';
          urlInput.placeholder = 'https://api.jina.ai/v1/rerank';
          urlInput.value = '';
        } else {
          keyRow.classList.add('hidden');
          urlLabel.textContent = 'Ollama 地址';
          urlInput.placeholder = 'http://localhost:11434';
          urlInput.value = 'http://localhost:11434';
        }
      },

      openModal(config) {
        const modal = document.getElementById('rerankModal');
        modal.classList.remove('hidden');
        modal.style.display = 'flex';
        document.getElementById('rerankModalTitle').textContent = config ? '编辑 Reranking 模型' : '添加 Reranking 模型';
        document.getElementById('rerankId').value = config ? config.id : '';
        document.getElementById('rerankName').value = config ? config.name : '';
        document.getElementById('rerankProvider').value = config ? (config.provider || 'ollama') : 'ollama';
        document.getElementById('rerankModel').value = config ? config.modelName : '';
        document.getElementById('rerankUrl').value = config ? (config.baseUrl || 'http://localhost:11434') : 'http://localhost:11434';
        document.getElementById('rerankKey').value = '';
        document.getElementById('rerankKey').placeholder = config ? '留空则不修改' : 'sk-...';
        App.settings.rerank.onProviderChange();
        if (config && config.provider === 'api') {
          document.getElementById('rerankUrl').value = config.baseUrl || '';
        }
      },

      closeModal() {
        document.getElementById('rerankModal').classList.add('hidden');
        document.getElementById('rerankModal').style.display = 'none';
      },

      async save(e) {
        e.preventDefault();
        const id = document.getElementById('rerankId').value;
        const body = {
          name: document.getElementById('rerankName').value.trim(),
          provider: document.getElementById('rerankProvider').value,
          modelName: document.getElementById('rerankModel').value.trim(),
          baseUrl: document.getElementById('rerankUrl').value.trim(),
          apiKey: document.getElementById('rerankKey').value,
          isActive: false,
        };
        if (!body.name || !body.modelName) { App.utils.toast('请填写必填字段', 'warning'); return; }
        try {
          const url = id ? '/api/reranking-configs/' + id : '/api/reranking-configs';
          const method = id ? 'PUT' : 'POST';
          const resp = await fetch(url, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
          if (resp.ok) {
            App.settings.rerank.closeModal();
            App.settings.rerank.load();
            App.utils.toast('保存成功', 'success');
          } else {
            App.utils.toast('保存失败: ' + resp.status, 'error');
          }
        } catch (err) { App.utils.toast('请求失败: ' + err.message, 'error'); }
      },

      async edit(id) {
        try {
          const resp = await fetch('/api/reranking-configs/' + id);
          if (resp.ok) App.settings.rerank.openModal(await resp.json());
        } catch (e) { App.utils.toast('获取配置失败', 'error'); }
      },

      async delete(id, name) {
        if (!confirm('确定删除 Reranking 配置 "' + name + '"？')) return;
        try {
          await fetch('/api/reranking-configs/' + id, { method: 'DELETE' });
          App.settings.rerank.load();
          App.utils.toast('删除成功', 'success');
        } catch (e) { App.utils.toast('删除失败', 'error'); }
      },

      async activate(id) {
        try {
          const r = await fetch('/api/reranking-configs/' + id + '/activate', { method: 'POST' });
          if (r.ok) App.settings.rerank.load();
        } catch (e) { App.utils.toast('激活失败', 'error'); }
      },

      async deactivate(id) {
        try {
          const r = await fetch('/api/reranking-configs/' + id + '/deactivate', { method: 'POST' });
          if (r.ok) App.settings.rerank.load();
        } catch (e) { App.utils.toast('停用失败', 'error'); }
      },

      async test(id, btnEl) {
        const btn = btnEl;
        const orig = btn.textContent;
        btn.disabled = true;
        btn.textContent = '测试中...';
        try {
          const resp = await fetch('/api/reranking-configs/' + id + '/test', { method: 'POST' });
          const result = await resp.json();
          if (result.success) {
            App.utils.toast('模型就绪！' + result.message, 'success');
          } else {
            App.utils.toast('测试失败: ' + result.message, 'error', 6000);
          }
        } catch (e) { App.utils.toast('测试异常: ' + e.message, 'error'); }
        finally { btn.disabled = false; btn.textContent = orig; }
      },
    },
  },

  init() {
    App.chat.loadModelList();
    const chatInput = document.getElementById('chatInput');
    if (chatInput) {
      chatInput.addEventListener('input', function () {
        this.style.height = 'auto';
        this.style.height = Math.min(this.scrollHeight, 200) + 'px';
        if (this.value.trim() === '') this.style.height = 'auto';
        const counter = document.getElementById('inputCharCount');
        if (counter) counter.textContent = this.value.length || '0';
      });
    }

    document.getElementById('llmModal')?.addEventListener('click', function (e) {
      if (e.target === e.currentTarget) App.settings.llm.closeModal();
    });
    document.getElementById('embedModal')?.addEventListener('click', function (e) {
      if (e.target === e.currentTarget) App.settings.embed.closeModal();
    });
    document.getElementById('rerankModal')?.addEventListener('click', function (e) {
      if (e.target === e.currentTarget) App.settings.rerank.closeModal();
    });
    document.getElementById('projectDetailModal')?.addEventListener('click', function (e) {
      if (e.target === e.currentTarget) App.ingestion.closeProjectDetail();
    });

    // 侧边栏展开/收起
    const sidebar = document.getElementById('sidebar');
    if (sidebar) {
      const isExpanded = localStorage.getItem('sidebarExpanded') === 'true';
      if (isExpanded) sidebar.classList.add('expanded');
    }

    // 预加载项目列表
    App.ingestion.loadProjects();

    // 初始化诊断栏事件委托
    App.chat._initDiagDelegation();

    // 恢复会话栏折叠状态
    App.chat._restoreSessionCol();

    // 加载聊天页状态条
    App.chat.loadStatusBar();
    App.chat._updateStatusSession();

    // 窗口 resize 时重新定位展开按钮
    window.addEventListener('resize', () => {
      const sessCol = document.getElementById('sessCol');
      if (sessCol && sessCol.classList.contains('collapsed')) {
        App.chat._positionExpandBtn();
      }
    });
  },

  toggleSidebar() {
    const sidebar = document.getElementById('sidebar');
    if (!sidebar) return;
    sidebar.classList.toggle('expanded');
    localStorage.setItem('sidebarExpanded', sidebar.classList.contains('expanded'));
    // sidebar 过渡 300ms，等完成后重新定位展开按钮
    const sessCol = document.getElementById('sessCol');
    if (sessCol && sessCol.classList.contains('collapsed')) {
      setTimeout(() => App.chat._positionExpandBtn(), 320);
    }
  },
};
document.addEventListener('DOMContentLoaded', App.init);