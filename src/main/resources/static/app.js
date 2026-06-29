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
    ['chat', 'dashboard', 'ingestion', 'settings'].forEach(id => {
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
    if (tabName === 'chat') { App.chat.loadModelList(); App.chat.loadSessions(); }
    if (tabName === 'ingestion') App.ingestion.loadProjects();
    if (tabName === 'settings') { App.settings.rag.load(); App.settings.llm.load(); App.settings.embed.load(); App.settings.rerank.load(); }
  },

  // ==================== 对话功能 ====================
  chat: {
    // ─── 会话管理 ───
    async loadSessions() {
      try {
        const resp = await App.utils.fetchWithRetry('/api/sessions');
        if (!resp.ok) return;
        const sessions = await resp.json();
        const list = document.getElementById('sessionList');
        if (!list) return;
        if (sessions.length === 0) {
          list.innerHTML = '<div class="text-[11px] text-gray-400 px-2 py-2">暂无历史会话</div>';
          return;
        }
        list.innerHTML = sessions.map(s => {
          const isActive = s.id === App.state.currentSessionId;
          const time = new Date(s.updatedAt).toLocaleString('zh-CN', { month:'numeric', day:'numeric', hour:'2-digit', minute:'2-digit' });
          return `<div class="session-item group flex items-center gap-2 px-2 py-1.5 rounded-lg cursor-pointer text-xs transition-colors ${isActive ? 'bg-primary/10 text-primary' : 'hover:bg-gray-100 text-gray-600'}" onclick="App.chat.switchSession('${s.id}')" title="${App.utils.escHtml(s.title)}">
            <svg class="w-3.5 h-3.5 flex-shrink-0 opacity-50" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"/></svg>
            <span class="flex-1 truncate sidebar-label opacity-0 w-0 transition-all duration-300">${App.utils.escHtml(s.title)}</span>
            <span class="sidebar-label text-[10px] text-gray-400 opacity-0 w-0 transition-all duration-300 flex-shrink-0">${time}</span>
            <button onclick="event.stopPropagation();App.chat.deleteSession('${s.id}')" class="sidebar-label opacity-0 w-0 transition-all duration-300 flex-shrink-0 text-gray-400 hover:text-red-500 p-0.5 rounded hover:bg-red-50" title="删除">
              <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/></svg>
            </button>
          </div>`;
        }).join('');
      } catch (e) { console.warn('加载会话列表失败', e); }
    },
    async switchSession(sessionId) {
      App.state.currentSessionId = sessionId;
      const container = document.getElementById('chatContainer');
      container.innerHTML = '';
      document.getElementById('chatWelcome')?.remove();
      try {
        const resp = await App.utils.fetchWithRetry(`/api/sessions/${sessionId}/messages`);
        if (!resp.ok) throw new Error('加载失败');
        const messages = await resp.json();
        for (const msg of messages) {
          if (msg.role === 'user') {
            container.insertAdjacentHTML('beforeend', `
              <div class="flex justify-end animate-slide-in mb-6">
                <div class="msg-user rounded-2xl px-5 py-3.5 max-w-[80%] shadow-sm">
                  <div class="text-[15px] leading-relaxed whitespace-pre-wrap">${App.utils.escHtml(msg.content)}</div>
                </div>
              </div>`);
          } else if (msg.role === 'assistant') {
            const wrapperId = 'ai-msg-' + App.utils.genId();
            container.insertAdjacentHTML('beforeend', `
              <div id="${wrapperId}" class="flex justify-start animate-fade-in mb-8">
                <div class="flex gap-4 w-full">
                  <div class="w-8 h-8 rounded-xl bg-primary flex items-center justify-center text-white flex-shrink-0 mt-1 shadow-md shadow-primary/20">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z"/></svg>
                  </div>
                  <div class="msg-ai flex-1 max-w-[calc(100%-3rem)]">
                    <div class="text-sm font-semibold text-gray-800 mb-1">智能助手</div>
                    <div class="ai-response prose prose-sm w-full"></div>
                  </div>
                </div>
              </div>`);
            const responseDiv = document.querySelector(`#${wrapperId} .ai-response`);
            responseDiv.innerHTML = marked.parse(msg.content);
            responseDiv.querySelectorAll('pre code').forEach(block => {
              if (!block.classList.contains('language-mermaid') && window.hljs) hljs.highlightElement(block);
            });
          }
        }
        const messagesEl = document.getElementById('chatMessages');
        messagesEl.scrollTo({ top: messagesEl.scrollHeight });
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
          App.state.currentSessionId = null;
          App.chat.clear();
        }
        this.loadSessions();
        App.utils.toast('会话已删除', 'info', 2000);
      } catch (e) { App.utils.toast('删除失败', 'error'); }
    },
    newChat() {
      App.state.currentSessionId = null;
      const container = document.getElementById('chatContainer');
      container.innerHTML = `<div id="chatWelcome" class="flex flex-col items-center justify-center py-32 text-center animate-fade-in"><div class="w-14 h-14 rounded-2xl bg-gradient-to-br from-primary/20 to-primary/5 flex items-center justify-center mb-6 shadow-sm border border-primary/10"><svg class="w-7 h-7 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"/></svg></div><h2 class="text-2xl font-semibold text-gray-800 mb-3 tracking-tight">有什么可以帮你的？</h2><p class="text-gray-500 text-sm max-w-md leading-relaxed">基于本地知识库的智能问答引擎，支持引用溯源、Markdown 与 Mermaid 可视化渲染。</p></div>`;
      this.loadSessions();
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
        }
      } catch (e) { document.getElementById('modelSelect').innerHTML = '<option value="">加载失败</option>'; }
    },
    onModelChange() {},
    onKeydown(e) {
      if (e.key === 'Enter' && !e.shiftKey) {
        if (e.isComposing) return;
        e.preventDefault();
        App.chat.send();
      }
    },
    async send() {
      const input = document.getElementById('chatInput');
      const query = input.value.trim();
      if (!query) return;
      input.value = '';
      input.style.height = 'auto';

      const modelKey = document.getElementById('modelSelect').value;
      const container = document.getElementById('chatContainer');
      const messagesEl = document.getElementById('chatMessages');

      document.getElementById('chatWelcome')?.remove();

      // 用户气泡
      container.insertAdjacentHTML('beforeend', `
        <div class="flex justify-end animate-slide-in mb-6">
          <div class="msg-user rounded-2xl px-5 py-3.5 max-w-[80%] shadow-sm">
            <div class="text-[15px] leading-relaxed whitespace-pre-wrap">${App.utils.escHtml(query)}</div>
          </div>
        </div>
      `);

      // AI 气泡结构 (预留 citations-container)
      const wrapperId = 'ai-msg-' + App.utils.genId();
      container.insertAdjacentHTML('beforeend', `
        <div id="${wrapperId}" class="flex justify-start animate-fade-in mb-8">
          <div class="flex gap-4 w-full">
            <div class="w-8 h-8 rounded-xl bg-primary flex items-center justify-center text-white flex-shrink-0 mt-1 shadow-md shadow-primary/20">
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z"/></svg>
            </div>
            <div class="msg-ai flex-1 max-w-[calc(100%-3rem)]">
              <div class="text-sm font-semibold text-gray-800 mb-1">智能助手</div>
              <div class="ai-response prose prose-sm w-full"><p class="text-gray-400 flex items-center gap-2"><span class="w-2 h-2 rounded-full bg-primary animate-ping"></span>思考并检索中...</p></div>
              <div class="citations-container citations-panel hidden"></div>
            </div>
          </div>
        </div>
      `);
      
      const responseDiv = document.querySelector(`#${wrapperId} .ai-response`);
      const citationsDiv = document.querySelector(`#${wrapperId} .citations-container`);
      messagesEl.scrollTo({ top: messagesEl.scrollHeight, behavior: 'smooth' });

      document.getElementById('sendBtn').disabled = true;
      App.state.currentSessionId = App.state.currentSessionId || App.utils.genId();
      App.state.currentSources = []; // 清空本次溯源

      try {
        const resp = await App.utils.fetchWithRetry('/api/chat/stream', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ query, modelKey, sessionId: App.state.currentSessionId }),
        }, 1);

        if (!resp.ok) throw new Error('请求失败 HTTP ' + resp.status);

        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        let fullText = '';

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          const chunk = decoder.decode(value, { stream: true });
          const lines = chunk.split('\n');
          for (const line of lines) {
            const t = line.trim();
            if (t.startsWith('data:') && t.length > 6) {
              try {
                // 假设后端传递 json：{ "text": "...", "sources": [{"id": 1, "name": "doc.md"}] }
                // 或者直接传文本，这里做个兼容处理
                const payload = JSON.parse(t.substring(5));
                if(payload.text) fullText += payload.text;
                if(payload.sources && payload.sources.length > 0) {
                   App.state.currentSources = payload.sources;
                   App.chat.renderCitations(citationsDiv, App.state.currentSources);
                }
                App.chat.scheduleRender(responseDiv, fullText);
              } catch (e) {
                // 回退为纯文本流式
                fullText += t.substring(5).replace(/^"|"$/g, '').replace(/\\n/g, '\n');
                App.chat.scheduleRender(responseDiv, fullText);
              }
              messagesEl.scrollTo({ top: messagesEl.scrollHeight });
            }
          }
        }
      } catch (e) {
        responseDiv.innerHTML = `<p class="text-red-500 text-sm">✕ ${App.utils.escHtml(e.message)}</p>`;
      } finally {
        document.getElementById('sendBtn').disabled = false;
        messagesEl.scrollTo({ top: messagesEl.scrollHeight, behavior: 'smooth' });
        this.loadSessions();
      }
    },
    scheduleRender(responseDiv, text) {
      App.state.pendingText = text;
      if (App.state.renderScheduled) return;
      App.state.renderScheduled = true;
      requestAnimationFrame(() => {
        if (responseDiv) {
          responseDiv.innerHTML = marked.parse(App.state.pendingText);
          App.chat.renderMermaid(responseDiv);
          responseDiv.querySelectorAll('pre code').forEach((block) => {
            if (!block.classList.contains('language-mermaid') && window.hljs) hljs.highlightElement(block);
          });
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
        wrapper.setAttribute('data-code', code);
        const id = 'mmd-' + Date.now() + '-' + idx;
        wrapper.id = id;
        block.parentNode.replaceWith(wrapper);
        if (window.mermaid) mermaid.render(id, code).then(({ svg }) => { wrapper.innerHTML = svg; }).catch(() => wrapper.innerHTML = '<div class="text-red-500 text-xs">图表渲染失败</div>');
      });
    },
    renderCitations(container, sources) {
      if(!sources || sources.length === 0) return;
      container.classList.remove('hidden');
      container.innerHTML = `<div class="w-full text-xs font-medium text-gray-400 mb-1 flex items-center gap-1"><svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"/></svg> 引用来源</div>` + 
      sources.map((s, i) => `
        <div class="citation-pill" title="${App.utils.escHtml(s.path || s.name)}">
          <span class="font-semibold text-[10px] bg-gray-200/50 px-1.5 rounded text-gray-500">[${i+1}]</span> 
          <span>${App.utils.escHtml(s.name)}</span>
        </div>
      `).join('');
    },
    clear() {
      App.state.currentSessionId = null;
      document.getElementById('chatContainer').innerHTML = `<div id="chatWelcome" class="flex flex-col items-center justify-center py-32 text-center animate-fade-in"><div class="w-14 h-14 rounded-2xl bg-gradient-to-br from-primary/20 to-primary/5 flex items-center justify-center mb-6 shadow-sm border border-primary/10"><svg class="w-7 h-7 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"/></svg></div><h2 class="text-2xl font-semibold text-gray-800 mb-3 tracking-tight">有什么可以帮你的？</h2><p class="text-gray-500 text-sm max-w-md leading-relaxed">基于本地知识库的智能问答引擎，支持引用溯源、Markdown 与 Mermaid 可视化渲染。</p></div>`;
      this.loadSessions();
      App.utils.toast('对话已清空', 'info', 2000);
    }
  },

  // ==================== 仪表盘功能 ====================
  dashboard: {
    refresh() {
      if (!App.state.mermaidInitialized) {
        try { if (window.mermaid) mermaid.run({ nodes: document.querySelectorAll('.mermaid:not([data-processed])') }); App.state.mermaidInitialized = true; } catch (e) {}
      }
      this.initECharts(); this.loadStats(); this.loadRecentQA();
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

  // ==================== 设 置 ====================
  settings: {
    // RAG 配置
    rag: {
      async load() {
        const form = document.getElementById('ragConfig');
        if (!form) return;
        try {
          const resp = await fetch('/api/configs');
          if (!resp.ok) { form.innerHTML = '<div class="text-red-500 text-sm p-4">加载失败</div>'; return; }
          const configs = await resp.json();
          if (configs.length === 0) { form.innerHTML = '<div class="text-gray-400 text-sm p-4">暂无配置项</div>'; return; }
          form.innerHTML = configs.map(c => {
            const id = 'rag_' + c.key;
            let input;
            if (c.type === 'boolean') {
              input = '<select id="' + id + '" class="border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-primary/20 focus:border-primary outline-none w-36 bg-white"><option value="true"' + (c.value === 'true' ? ' selected' : '') + '>开启</option><option value="false"' + (c.value === 'false' ? ' selected' : '') + '>关闭</option></select>';
            } else if (c.type === 'number') {
              input = '<input id="' + id + '" type="number" class="border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-primary/20 focus:border-primary outline-none w-44 bg-white" value="' + App.utils.escHtml(c.value) + '" step="' + (c.value.includes('.') ? '0.01' : '1') + '" min="0.01">';
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
            return;
          }

          tbody.innerHTML = configs.map(c =>
            `<tr class="border-b border-gray-100 hover:bg-gray-50/50 transition-colors">
              <td class="px-4 py-3 font-medium text-gray-800 text-sm">${App.utils.escHtml(c.name)}</td>
              <td class="px-4 py-3 text-sm text-gray-600">${App.utils.escHtml(c.modelName || '—')}</td>
              <td class="px-4 py-3 text-sm text-gray-500 font-mono">${App.utils.escHtml(c.ollamaUrl || '—')}</td>
              <td class="px-4 py-3">${c.isActive ? '<span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-50 text-green-700 border border-green-200">● 已激活</span>' : '<span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-500 border border-gray-200">○ 未激活</span>'}</td>
              <td class="px-4 py-2.5 text-right"><div class="flex gap-1.5 justify-end flex-wrap">
                <button class="px-2 py-1 text-xs border border-gray-200 text-gray-600 rounded hover:bg-gray-50 hover:border-gray-300 transition-colors" onclick="App.settings.rerank.test('${c.id}', this)">测试</button>
                ${c.isActive
                  ? `<button class="px-2 py-1 text-xs border border-gray-200 text-gray-500 rounded hover:bg-gray-50 transition-colors" onclick="App.settings.rerank.deactivate('${c.id}')">停用</button>`
                  : `<button class="px-2 py-1 text-xs border border-primary text-primary rounded hover:bg-green-50 transition-colors" onclick="App.settings.rerank.activate('${c.id}')">激活</button>`}
                <button class="px-2 py-1 text-xs border border-gray-200 text-gray-600 rounded hover:bg-gray-50 transition-colors" onclick="App.settings.rerank.edit('${c.id}')">编辑</button>
                <button class="px-2 py-1 text-xs border border-red-200 text-red-600 rounded hover:bg-red-50 transition-colors" onclick="App.settings.rerank.delete('${c.id}','${App.utils.escHtml(c.name)}')">删除</button>
              </div></td></tr>`
          ).join('');

          hint.classList.toggle('hidden', hasActive);
          App.settings.rerank.syncEnableReranking(hasActive);
        } catch (e) { console.error('加载 Reranking 配置失败:', e); }
      },

      syncEnableReranking(hasActive) {
        const el = document.getElementById('rag_enable_reranking');
        if (el && !hasActive) {
          el.value = 'false';
        }
      },

      openModal(config) {
        const modal = document.getElementById('rerankModal');
        modal.classList.remove('hidden');
        modal.style.display = 'flex';
        document.getElementById('rerankModalTitle').textContent = config ? '编辑 Reranking 模型' : '添加 Reranking 模型';
        document.getElementById('rerankId').value = config ? config.id : '';
        document.getElementById('rerankName').value = config ? config.name : '';
        document.getElementById('rerankModel').value = config ? config.modelName : '';
        document.getElementById('rerankUrl').value = config ? (config.ollamaUrl || 'http://localhost:11434') : 'http://localhost:11434';
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
          modelName: document.getElementById('rerankModel').value.trim(),
          ollamaUrl: document.getElementById('rerankUrl').value.trim(),
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
      App._syncSessionListVisibility();
    }

    // 预加载项目列表
    App.ingestion.loadProjects();
  },

  toggleSidebar() {
    const sidebar = document.getElementById('sidebar');
    if (!sidebar) return;
    sidebar.classList.toggle('expanded');
    localStorage.setItem('sidebarExpanded', sidebar.classList.contains('expanded'));
    this._syncSessionListVisibility();
  },
  _syncSessionListVisibility() {
    const sidebar = document.getElementById('sidebar');
    const wrap = document.getElementById('sessionListWrap');
    if (!sidebar || !wrap) return;
    const expanded = sidebar.classList.contains('expanded');
    if (expanded) {
      wrap.classList.remove('hidden');
      App.chat.loadSessions();
    } else {
      wrap.classList.add('hidden');
    }
  },
};
document.addEventListener('DOMContentLoaded', App.init);