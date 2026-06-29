// ==================== APP 命名空间 ====================
const App = {
  state: {
    currentTab: 'chat',
    models: [],
    llmConfigs: [],
    embedConfigs: [],
    projects: [],
    activeLLM: null,
    activeEmbed: null,
    chartInstance: null,
    mermaidInitialized: false,
    projectIndex: 0,
    qaHistory: [],
    totalChunks: 0,
    totalFiles: 0,
    lastProcessTime: null,
    renderScheduled: false,
    pendingText: '',
  },

  // ==================== 工具函数 ====================
  utils: {
    escHtml(str) {
      if (!str) return '';
      const d = document.createElement('div');
      d.textContent = String(str);
      return d.innerHTML;
    },

    toast(msg, type = 'info', duration = 4000) {
      const icons = { success: '✓', error: '✕', info: 'ℹ', warning: '⚠' };
      const container = document.getElementById('toastContainer');
      if (!container) return;
      const el = document.createElement('div');
      el.className = 'toast show ' + type;
      el.innerHTML = '<span class="flex-shrink-0">' + (icons[type] || icons.info) + '</span><span class="flex-1">' + App.utils.escHtml(msg) + '</span>';
      container.appendChild(el);
      setTimeout(() => {
        el.classList.remove('show');
        setTimeout(() => el.remove(), 300);
      }, duration);
    },

    async fetchWithRetry(url, options = {}, maxRetries = 2) {
      let lastError;
      for (let i = 0; i <= maxRetries; i++) {
        try {
          const resp = await fetch(url, options);
          return resp;
        } catch (err) {
          lastError = err;
          if (i < maxRetries) {
            await new Promise(r => setTimeout(r, 1000 * Math.pow(2, i)));
          }
        }
      }
      throw lastError;
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
    },
  },

  // ==================== Tab 切换 ====================
  switchTab(tabName) {
    ['chat', 'dashboard', 'ingestion', 'settings'].forEach(id => {
      const el = document.getElementById('tab-' + id);
      if (el) el.classList.add('hidden');
    });
    const target = document.getElementById('tab-' + tabName);
    if (target) {
      target.classList.remove('hidden');
      target.classList.add('animate-fade-in');
    }
    document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
    document.querySelectorAll('.tab-btn[onclick*="' + tabName + '"]').forEach(btn => btn.classList.add('active'));
    App.state.currentTab = tabName;
    if (tabName === 'dashboard') App.dashboard.refresh();
    if (tabName === 'chat') App.chat.loadModelList();
    if (tabName === 'settings') {
      App.settings.rag.load();
      App.settings.llm.load();
      App.settings.embed.load();
    }
  },

  // ==================== 对话模块 ====================
  chat: {
    async loadModelList() {
      try {
        const resp = await App.utils.fetchWithRetry('/api/models');
        if (resp.ok) {
          const models = await resp.json();
          App.state.models = models;
          const select = document.getElementById('modelSelect');
          select.innerHTML = models.length > 0
            ? models.map(m => '<option value="' + m.id + '">' + App.utils.escHtml(m.name) + ' (' + App.utils.escHtml(m.modelName) + ')</option>').join('')
            : '<option value="">请先在设置中激活模型</option>';
        }
      } catch (e) {
        document.getElementById('modelSelect').innerHTML = '<option value="">模型加载失败</option>';
      }
    },

    onModelChange() {},

    onKeydown(e) {
      if (e.key === 'Enter' && !e.shiftKey) {
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

      const welcome = document.getElementById('chatWelcome');
      if (welcome) welcome.remove();

      const userDiv = document.createElement('div');
      userDiv.className = 'flex justify-end animate-fade-in';
      userDiv.innerHTML = '<div class="msg-user rounded-2xl rounded-tr-sm px-4 py-3 max-w-[75%] shadow-sm"><div class="text-sm leading-relaxed whitespace-pre-wrap">' + App.utils.escHtml(query) + '</div></div>';
      container.appendChild(userDiv);

      const aiWrapper = document.createElement('div');
      aiWrapper.className = 'flex justify-start animate-fade-in';
      aiWrapper.innerHTML = '<div class="flex gap-3 max-w-full"><div class="w-8 h-8 rounded-full bg-gray-100 border border-gray-200 flex items-center justify-center text-xs text-gray-500 font-medium flex-shrink-0 mt-1">AI</div><div class="msg-ai rounded-2xl rounded-tl-sm px-4 py-3 max-w-[75%] shadow-sm"><div class="text-xs text-gray-400 mb-1 font-medium">RAG Assistant</div><div class="ai-response prose prose-sm"><p class="text-gray-400 animate-pulse">思考中...</p></div></div></div>';
      container.appendChild(aiWrapper);
      const responseDiv = aiWrapper.querySelector('.ai-response');
      messagesEl.scrollTop = messagesEl.scrollHeight;

      const btn = document.getElementById('sendBtn');
      btn.disabled = true;

      try {
        const resp = await App.utils.fetchWithRetry('/api/chat/stream', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ query, modelKey }),
        }, 1);

        if (!resp.ok) {
          let errorMsg = '请求失败 (HTTP ' + resp.status + ')';
          try { const eb = await resp.json(); errorMsg = eb.message || eb.error || errorMsg; } catch (e) {}
          responseDiv.innerHTML = '<p class="text-red-500 text-sm">✕ ' + App.utils.escHtml(errorMsg) + '</p>';
          return;
        }

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
              fullText += t.substring(6);
              App.chat.scheduleRender(responseDiv, fullText);
            } else if (t.startsWith('data:{"error"')) {
              fullText += t.substring(6);
              App.chat.scheduleRender(responseDiv, fullText);
            }
          }
        }

        const selectedModel = App.state.models.find(m => m.id === modelKey);
        const modelName = selectedModel ? selectedModel.modelName : 'unknown';
        App.state.qaHistory.unshift({ question: query, answer: fullText, time: new Date().toLocaleTimeString() });
        fetch('/api/chat/save', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ question: query, answer: fullText, modelName }),
        }).catch(() => {});

      } catch (e) {
        responseDiv.innerHTML = '<p class="text-red-500 text-sm">请求失败: ' + App.utils.escHtml(e.message) + '</p>';
      } finally {
        btn.disabled = false;
        messagesEl.scrollTop = messagesEl.scrollHeight;
      }
    },

    scheduleRender(responseDiv, text) {
      App.state.pendingText = text;
      if (App.state.renderScheduled) return;
      App.state.renderScheduled = true;
      requestAnimationFrame(() => {
        if (!responseDiv) { App.state.renderScheduled = false; return; }
        try {
          responseDiv.innerHTML = marked.parse(App.state.pendingText);
          App.chat.renderMermaid(responseDiv);
        } catch (e) {
          responseDiv.textContent = App.state.pendingText;
        }
        App.state.renderScheduled = false;
      });
    },

    renderMermaid(container) {
      if (!container) return;
      const existing = container.querySelectorAll('.mermaid-wrapper');
      existing.forEach(el => {
        const code = el.getAttribute('data-code');
        if (code && window.mermaid) {
          const id = 'mmd-' + Date.now() + '-' + Math.random().toString(36).slice(2, 6);
          mermaid.render(id, code).then(({ svg }) => { el.innerHTML = svg; }).catch(() => {});
        }
      });
      const blocks = container.querySelectorAll('pre code.language-mermaid');
      blocks.forEach((block, idx) => {
        const graphDef = block.textContent.trim();
        if (!graphDef) return;
        const wrapper = document.createElement('div');
        wrapper.className = 'my-4 flex justify-center mermaid-wrapper';
        wrapper.setAttribute('data-code', graphDef);
        const id = 'mmd-' + Date.now() + '-' + idx;
        wrapper.id = id;
        block.parentNode.replaceWith(wrapper);
        if (window.mermaid) {
          mermaid.render(id, graphDef).then(({ svg }) => { wrapper.innerHTML = svg; }).catch(() => {
            wrapper.innerHTML = '<div class="text-red-500 text-sm p-2 border border-red-200 rounded bg-red-50">Mermaid 渲染失败</div>';
          });
        }
      });
    },

    clear() {
      const container = document.getElementById('chatContainer');
      container.innerHTML = '<div id="chatWelcome" class="flex flex-col items-center justify-center py-20 text-center"><div class="w-12 h-12 rounded-xl bg-primary/10 flex items-center justify-center mb-4"><svg class="w-6 h-6 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"/></svg></div><h2 class="text-xl font-semibold text-gray-900 mb-2">有什么可以帮你的？</h2><p class="text-gray-500 text-sm max-w-md">基于你的知识库进行智能问答，支持 Markdown 格式回复和 Mermaid 图表渲染。</p></div>';
    },
  },

  // ==================== 仪表盘模块 ====================
  dashboard: {
    refresh() {
      if (!App.state.mermaidInitialized) {
        try {
          if (window.mermaid) mermaid.run({ nodes: document.querySelectorAll('.mermaid:not([data-processed])') });
          App.state.mermaidInitialized = true;
        } catch (e) { console.warn('Mermaid init:', e); }
      }
      App.dashboard.initECharts();
      App.dashboard.loadStats();
      App.dashboard.loadRecentQA();
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
        document.getElementById('statFiles').textContent = App.state.totalFiles || 0;
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
            '<div class="p-3 bg-gray-50 rounded-lg border border-gray-100 animate-fade-in"><div class="text-sm font-medium text-gray-800 truncate">' + App.utils.escHtml(qa.question) + '</div><div class="text-xs text-gray-500 mt-1 line-clamp-2">' + App.utils.escHtml(qa.answer || '') + '</div><div class="text-xs text-gray-400 mt-1.5">' + (qa.modelName || '') + (qa.createdAt ? ' · ' + new Date(qa.createdAt).toLocaleString() : '') + '</div></div>'
          ).join('');
        }
      } catch (e) {
        if (App.state.qaHistory.length === 0) {
          container.innerHTML = '<div class="text-sm text-gray-400 text-center py-8">暂无记录</div>';
        } else {
          container.innerHTML = App.state.qaHistory.slice(0, 10).map(qa =>
            '<div class="p-3 bg-gray-50 rounded-lg border border-gray-100 animate-fade-in"><div class="text-sm font-medium text-gray-800 truncate">' + App.utils.escHtml(qa.question) + '</div><div class="text-xs text-gray-500 mt-1 line-clamp-2">' + App.utils.escHtml(qa.answer.substring(0, 120)) + (qa.answer.length > 120 ? '...' : '') + '</div><div class="text-xs text-gray-400 mt-1.5">' + qa.time + '</div></div>'
          ).join('');
        }
      }
    },

    initECharts() {
      const dom = document.getElementById('chartContainer');
      if (!dom) return;
      if (App.state.chartInstance) { App.state.chartInstance.dispose(); App.state.chartInstance = null; }
      if (!window.echarts) return;
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
        xAxis: { type: 'category', data: ['Java', 'JavaScript', 'TypeScript', 'Python', 'SQL', 'XML'], axisLabel: { color: '#9ca3af', rotate: 30, fontSize: 11 }, axisLine: { lineStyle: { color: '#e5e7eb' } } },
        yAxis: { type: 'value', name: 'Chunks', nameTextStyle: { color: '#9ca3af' }, axisLabel: { color: '#9ca3af' }, splitLine: { lineStyle: { color: '#f3f4f6' } } },
        series: [{ type: 'bar', data: [0, 0, 0, 0, 0, 0], itemStyle: { color: '#e5e7eb', borderRadius: [4, 4, 0, 0] }, barWidth: '40%' }],
      });
    },
  },

  // ==================== 文档入库模块 ====================
  ingestion: {
    getProjects() {
      const rows = document.querySelectorAll('#projectList > div');
      const projects = [];
      rows.forEach(row => {
        const inputs = row.querySelectorAll('input');
        const name = inputs[0] ? inputs[0].value.trim() : '';
        const path = inputs[1] ? inputs[1].value.trim() : '';
        if (name && path) projects.push({ name, path });
      });
      return projects;
    },

    addProject() {
      const nameEl = document.getElementById('projectName');
      const pathEl = document.getElementById('projectPath');
      const name = nameEl ? nameEl.value.trim() : '';
      const path = pathEl ? pathEl.value.trim() : '';
      if (!name || !path) {
        App.utils.toast('请填写项目名称和路径', 'warning');
        nameEl?.focus();
        return;
      }
      const container = document.getElementById('projectList');
      const idx = App.state.projectIndex++;
      const row = document.createElement('div');
      row.className = 'flex flex-col sm:flex-row gap-3 p-3 bg-gray-50 rounded-lg border border-gray-100 animate-fade-in';
      row.id = 'project-' + idx;
      row.innerHTML =
        '<div class="flex-1"><input class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" placeholder="项目名称" value="' + App.utils.escHtml(name) + '"></div>' +
        '<div class="flex-[2]"><input class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" placeholder="本地路径，如 D:\\code\\myproject" value="' + App.utils.escHtml(path) + '"></div>' +
        '<button class="self-start sm:self-center p-2 text-gray-400 hover:text-red-500 hover:bg-red-50 rounded-lg transition-colors flex-shrink-0" onclick="App.ingestion.removeProject(' + idx + ')" title="删除">' +
        '<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/></svg></button>';
      container.appendChild(row);
      nameEl.value = '';
      pathEl.value = '';
      nameEl.focus();
    },

    removeProject(idx) {
      const row = document.getElementById('project-' + idx);
      if (row) row.remove();
    },

    async start() {
      const projects = App.ingestion.getProjects();
      if (projects.length === 0) {
        App.utils.toast('请至少添加一个项目（名称 + 路径）', 'warning');
        return;
      }

      const btn = document.getElementById('ingestBtn');
      btn.disabled = true;
      btn.textContent = '入库中...';

      const progressEl = document.getElementById('ingestProgress');
      progressEl.classList.remove('hidden');
      App.ingestion.updateProgress(0, '准备中...', '—');
      App.ingestion.addLog('info', '开始入库，共 ' + projects.length + ' 个项目...');

      try {
        for (const project of projects) {
          App.ingestion.addLog('info', '── 项目: ' + project.name + ' (' + project.path + ')');

          try {
            const resp = await App.utils.fetchWithRetry('/api/ingestion/start', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ paths: [project.path], projectName: project.name }),
            }, 1);

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
                    App.ingestion.addLog(data.status || 'info', data.message || '');
                    if (data.stats) {
                      const s = data.stats;
                      App.ingestion.updateProgress(s.progressPercentage || 0, s.currentFile || data.message || '', (s.successFiles || 0) + ' / ' + ((s.successFiles || 0) + (s.failedFiles || 0) + (s.skippedFiles || 0)) + ' 文件');
                      App.state.totalChunks = (App.state.totalChunks || 0) + (s.processedFiles || 0);
                    }
                    if (data.status === 'done') App.utils.toast(data.message || '项目入库完成', 'success', 6000);
                  } catch (e) {}
                }
              }
            }
          } catch (e) {
            App.ingestion.addLog('error', '请求失败: ' + e.message);
            App.utils.toast('入库请求失败: ' + e.message, 'error');
          }
        }
      } finally {
        App.state.lastProcessTime = new Date().toLocaleTimeString();
        App.ingestion.addLog('done', '全部入库完成！');
        btn.disabled = false;
        btn.textContent = '开始入库';
      }
    },

    updateProgress(pct, status, count) {
      const bar = document.getElementById('ingestBar');
      const pctEl = document.getElementById('ingestPercent');
      const statusEl = document.getElementById('ingestStatus');
      const fileEl = document.getElementById('ingestFile');
      const countEl = document.getElementById('ingestCount');
      if (bar) bar.style.width = pct + '%';
      if (pctEl) pctEl.textContent = pct + '%';
      if (statusEl) statusEl.textContent = status || '处理中...';
      if (fileEl) fileEl.textContent = status || '—';
      if (countEl) countEl.textContent = count || '0 / 0 文件';
    },

    addLog(status, message) {
      const container = document.getElementById('ingestLog');
      if (!container) return;
      const time = new Date().toLocaleTimeString();
      const icons = { info: 'ℹ', processing: '⚙', success: '✓', error: '✗', skip: '⊘', done: '✔' };
      const icon = icons[status] || '•';
      const colors = { info: 'text-gray-500', processing: 'text-primary', success: 'text-green-600', error: 'text-red-500', skip: 'text-amber-600', done: 'text-green-700' };
      const color = colors[status] || 'text-gray-500';
      const div = document.createElement('div');
      div.className = 'log-line ' + color + ' text-xs leading-relaxed';
      div.textContent = '[' + time + '] ' + icon + ' ' + message;
      container.appendChild(div);
      container.scrollTop = container.scrollHeight;
    },
  },

  // ==================== 设置模块 ====================
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
            tbody.innerHTML = '<tr><td colspan="6" class="px-4 py-8 text-center text-gray-400 text-sm">暂无配置，点击"添加配置"创建</td></tr>';
            return;
          }
          tbody.innerHTML = configs.map(c =>
            '<tr class="border-b border-gray-100 hover:bg-gray-50/50 transition-colors">' +
            '<td class="px-4 py-3 font-medium text-gray-800 text-sm">' + App.utils.escHtml(c.name) + '</td>' +
            '<td class="px-4 py-3 text-sm text-gray-600">' + App.utils.escHtml(c.modelName || '—') + '</td>' +
            '<td class="px-4 py-3 text-sm text-gray-500 max-w-[200px] truncate" title="' + App.utils.escHtml(c.baseUrl || '') + '">' + App.utils.escHtml(c.baseUrl || '—') + '</td>' +
            '<td class="px-4 py-3 text-xs">' + (c.supportsStreaming ? '<span class="text-green-600">✓ 流式</span>' : '<span class="text-amber-600">✗ 非流式</span>') + '</td>' +
            '<td class="px-4 py-3">' + (c.isActive ? '<span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-50 text-green-700 border border-green-200">● 已激活</span>' : '<span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-500 border border-gray-200">○ 未激活</span>') + '</td>' +
            '<td class="px-4 py-2.5 text-right"><div class="flex gap-1.5 justify-end flex-wrap">' +
            '<button class="px-2 py-1 text-xs border border-gray-200 text-gray-600 rounded hover:bg-gray-50 hover:border-gray-300 transition-colors" onclick="App.settings.llm.test(\'' + c.id + '\', this)">测试</button>' +
            (c.isActive
              ? '<button class="px-2 py-1 text-xs border border-gray-200 text-gray-500 rounded hover:bg-gray-50 transition-colors" onclick="App.settings.llm.deactivate(\'' + c.id + '\')">停用</button>'
              : '<button class="px-2 py-1 text-xs border border-primary text-primary rounded hover:bg-green-50 transition-colors" onclick="App.settings.llm.activate(\'' + c.id + '\')">激活</button>') +
            '<button class="px-2 py-1 text-xs border border-gray-200 text-gray-600 rounded hover:bg-gray-50 transition-colors" onclick="App.settings.llm.edit(\'' + c.id + '\')">编辑</button>' +
            '<button class="px-2 py-1 text-xs border border-red-200 text-red-600 rounded hover:bg-red-50 transition-colors" onclick="App.settings.llm.delete(\'' + c.id + '\',\'' + App.utils.escHtml(c.name) + '\')">删除</button>' +
            '</div></td></tr>'
          ).join('');
        } catch (e) {
          document.getElementById('llmTable').innerHTML = '<tr><td colspan="6" class="px-4 py-8 text-center text-red-500 text-sm">加载失败</td></tr>';
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
            tbody.innerHTML = '<tr><td colspan="5" class="px-4 py-8 text-center text-gray-400 text-sm">暂无配置，点击"添加配置"创建</td></tr>';
            return;
          }
          tbody.innerHTML = configs.map(c =>
            '<tr class="border-b border-gray-100 hover:bg-gray-50/50 transition-colors">' +
            '<td class="px-4 py-3 font-medium text-gray-800 text-sm">' + App.utils.escHtml(c.name) + '</td>' +
            '<td class="px-4 py-3"><span class="inline-flex px-2 py-1 bg-gray-100 rounded text-xs text-gray-600 border border-gray-200">' + (c.provider === 'ollama' ? 'Ollama' : 'OpenAI') + '</span></td>' +
            '<td class="px-4 py-3 text-sm text-gray-600">' + App.utils.escHtml(c.modelName || '—') + '</td>' +
            '<td class="px-4 py-3">' + (c.isActive ? '<span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-50 text-green-700 border border-green-200">● 已激活</span>' : '<span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-500 border border-gray-200">○ 未激活</span>') + '</td>' +
            '<td class="px-4 py-2.5 text-right"><div class="flex gap-1.5 justify-end flex-wrap">' +
            '<button class="px-2 py-1 text-xs border border-gray-200 text-gray-600 rounded hover:bg-gray-50 hover:border-gray-300 transition-colors" onclick="App.settings.embed.test(\'' + c.id + '\', this)">测试</button>' +
            (c.isActive
              ? '<button class="px-2 py-1 text-xs border border-gray-200 text-gray-500 rounded hover:bg-gray-50 transition-colors" onclick="App.settings.embed.deactivate(\'' + c.id + '\')">停用</button>'
              : '<button class="px-2 py-1 text-xs border border-primary text-primary rounded hover:bg-green-50 transition-colors" onclick="App.settings.embed.activate(\'' + c.id + '\')">激活</button>') +
            '<button class="px-2 py-1 text-xs border border-gray-200 text-gray-600 rounded hover:bg-gray-50 transition-colors" onclick="App.settings.embed.edit(\'' + c.id + '\')">编辑</button>' +
            '<button class="px-2 py-1 text-xs border border-red-200 text-red-600 rounded hover:bg-red-50 transition-colors" onclick="App.settings.embed.delete(\'' + c.id + '\',\'' + App.utils.escHtml(c.name) + '\')">删除</button>' +
            '</div></td></tr>'
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
  },

  // ==================== 初始化 ====================
  init() {
    try { if (window.mermaid) mermaid.initialize({ theme: 'dark', startOnLoad: false }); } catch (e) {}
    App.chat.loadModelList();

    const chatInput = document.getElementById('chatInput');
    if (chatInput) {
      chatInput.addEventListener('input', function () {
        this.style.height = 'auto';
        this.style.height = Math.min(this.scrollHeight, 200) + 'px';
      });
    }

    document.getElementById('llmModal').addEventListener('click', function (e) {
      if (e.target === e.currentTarget) App.settings.llm.closeModal();
    });
    document.getElementById('embedModal').addEventListener('click', function (e) {
      if (e.target === e.currentTarget) App.settings.embed.closeModal();
    });

  },
};

document.addEventListener('DOMContentLoaded', App.init);
