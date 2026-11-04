package com.watchreader

/**
 * 局域网传书网页（单文件内联 HTML/CSS/JS，无外部请求）。
 *
 * 独立成文件的原因：这段页面是纯静态资源，与 socket 生命周期、路由、上传逻辑无关，
 * 留在 WifiTransferServer 里只会让「协议实现」和「前端资源」互相淹没。
 *
 * 页面内的 ${'$'} 写法用于在原始字符串里输出 JS 模板字面量的美元符，运行时原样落到浏览器。
 */
internal val TRANSFER_PAGE_HTML: String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>WatchReader 腕上无线传书</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
        body { background: #0f1117; color: #e1e4ea; display: flex; flex-direction: column; align-items: center; min-height: 100vh; padding: 24px 16px; }
        .card { background: #1a1d26; border-radius: 16px; width: 100%; max-width: 520px; padding: 28px; box-shadow: 0 10px 30px rgba(0,0,0,0.5); border: 1px solid #2d3139; }
        .title { font-size: 20px; font-weight: 700; color: #58a6ff; text-align: center; margin-bottom: 8px; }
        .desc { font-size: 13px; color: #8b949e; text-align: center; margin-bottom: 24px; }
        .drop-zone { border: 2px dashed #388bfd; border-radius: 12px; padding: 36px 20px; text-align: center; background: rgba(56,139,253,0.04); cursor: pointer; transition: all 0.2s ease; margin-bottom: 20px; }
        .drop-zone:hover, .drop-zone.dragover { background: rgba(56,139,253,0.12); border-color: #58a6ff; }
        .drop-text { font-size: 15px; font-weight: 600; color: #c9d1d9; margin-bottom: 6px; }
        .drop-sub { font-size: 12px; color: #8b949e; }
        .btn { background: #238636; color: #ffffff; border: none; padding: 12px 20px; border-radius: 8px; font-size: 15px; font-weight: 600; cursor: pointer; width: 100%; transition: background 0.2s; }
        .btn:hover { background: #2ea043; }
        .btn:disabled { background: #234c2e; color: #8b949e; cursor: not-allowed; }
        #fileInput { display: none; }
        .file-list { margin-top: 20px; list-style: none; }
        .file-item { display: flex; flex-direction: column; background: #21262d; padding: 12px 14px; border-radius: 8px; font-size: 13px; margin-bottom: 8px; }
        .file-header { display: flex; justify-content: space-between; align-items: center; width: 100%; }
        .file-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 70%; font-weight: 500; }
        .file-status { font-weight: 600; font-size: 12px; }
        .success { color: #3fb950; }
        .error { color: #f85149; }
        .progress-track { height: 6px; background: #30363d; border-radius: 3px; overflow: hidden; margin-top: 8px; display: none; width: 100%; }
        .progress-fill { height: 100%; background: #388bfd; width: 0%; transition: width 0.15s ease-out; }
        .del-btn { background: #da3633; color: #ffffff; border: none; padding: 4px 10px; border-radius: 6px; font-size: 12px; cursor: pointer; transition: background 0.2s; }
        .del-btn:hover { background: #b62324; }
        .down-btn { background: #238636; color: #ffffff; text-decoration: none; padding: 4px 10px; border-radius: 6px; font-size: 12px; cursor: pointer; transition: background 0.2s; display: inline-block; }
        .down-btn:hover { background: #2ea043; }
        .book-meta { font-size: 11px; color: #8b949e; margin-top: 2px; }
    </style>
</head>
<body>
    <div class="card">
        <div class="title">WatchReader 腕上无线传书</div>
        <div class="desc">支持将本地 TXT / EPUB / MOBI / FB2 / HTML 小说直接无线推送到手表书架</div>
        <div class="drop-zone" id="dropZone">
            <div class="drop-text">点击或拖拽小说文件到此处</div>
            <div class="drop-sub">支持 .txt、.epub、.mobi、.azw3、.fb2、.html 格式（自动排版）</div>
        </div>
        <input type="file" id="fileInput" multiple accept=".txt,.md,.epub,.mobi,.azw3,.azw,.prc,.fb2,.html,.htm">
        <button class="btn" id="uploadBtn" disabled>开始传输到手表</button>
        <ul class="file-list" id="fileList"></ul>
    </div>

    <div class="card" style="margin-top: 20px;">
        <div class="title" style="font-size: 17px; margin-bottom: 4px;">手表已存书籍管理</div>
        <div class="desc" style="margin-bottom: 14px;">查看手表内已存书架，支持网页一键清理</div>
        <div id="shelfLoading" style="text-align:center; color:#8b949e; font-size:13px; padding:12px;">正在加载书架...</div>
        <ul class="file-list" id="shelfList"></ul>
    </div>

    <script>
        // 访问令牌：从进入 URL 的 query 中取得，所有接口调用统一附带，
        // 手表端逐请求校验，防止同网段未授权访问
        const TOKEN = new URLSearchParams(location.search).get('token') || '';
        function withToken(url) {
            return url + (url.indexOf('?') >= 0 ? '&' : '?') + 'token=' + encodeURIComponent(TOKEN);
        }

        const dropZone = document.getElementById('dropZone');
        const fileInput = document.getElementById('fileInput');
        const uploadBtn = document.getElementById('uploadBtn');
        const fileList = document.getElementById('fileList');
        let selectedFiles = [];

        dropZone.addEventListener('click', () => fileInput.click());
        dropZone.addEventListener('dragover', (e) => { e.preventDefault(); dropZone.classList.add('dragover'); });
        dropZone.addEventListener('dragleave', () => dropZone.classList.remove('dragover'));
        dropZone.addEventListener('drop', (e) => {
            e.preventDefault();
            dropZone.classList.remove('dragover');
            handleFiles(e.dataTransfer.files);
        });
        fileInput.addEventListener('change', (e) => handleFiles(e.target.files));

        function handleFiles(files) {
            for (let f of files) {
                if (/\.(txt|md|epub|mobi|azw3|azw|prc|fb2|html|htm)$/i.test(f.name)) {
                    selectedFiles.push(f);
                    const li = document.createElement('li');
                    li.className = 'file-item';
                    li.innerHTML = `
                        <div class="file-header">
                            <span class="file-name">${'$'}{f.name}</span>
                            <span class="file-status" style="color:#8b949e">待传输</span>
                        </div>
                        <div class="progress-track"><div class="progress-fill"></div></div>
                    `;
                    fileList.appendChild(li);
                }
            }
            if (selectedFiles.length > 0) uploadBtn.disabled = false;
        }

        uploadBtn.addEventListener('click', async () => {
            uploadBtn.disabled = true;
            const items = fileList.querySelectorAll('.file-item');

            for (let i = 0; i < selectedFiles.length; i++) {
                const file = selectedFiles[i];
                const item = items[i];
                const statusEl = item.querySelector('.file-status');
                const progressTrack = item.querySelector('.progress-track');
                const progressFill = item.querySelector('.progress-fill');

                progressTrack.style.display = 'block';
                statusEl.innerText = '正在传输 0%...';
                statusEl.style.color = '#58a6ff';

                await new Promise((resolve) => {
                    const xhr = new XMLHttpRequest();
                    const uploadUrl = withToken('/upload?filename=' + encodeURIComponent(file.name));
                    xhr.open('POST', uploadUrl, true);
                    xhr.setRequestHeader('Content-Type', 'application/octet-stream');
                    xhr.setRequestHeader('X-Filename', encodeURIComponent(file.name));

                    xhr.upload.onprogress = (e) => {
                        if (e.lengthComputable) {
                            const pct = Math.round((e.loaded / e.total) * 100);
                            progressFill.style.width = pct + '%';
                            statusEl.innerText = `正在传输 ${'$'}{pct}%...`;
                        }
                    };

                    xhr.onload = () => {
                        if (xhr.status === 200) {
                            progressFill.style.width = '100%';
                            progressFill.style.background = '#3fb950';
                            statusEl.innerText = '已送达手表';
                            statusEl.className = 'file-status success';
                        } else {
                            statusEl.innerText = '上传失败';
                            statusEl.className = 'file-status error';
                        }
                        resolve();
                    };

                    xhr.onerror = () => {
                        statusEl.innerText = '连接中断';
                        statusEl.className = 'file-status error';
                        resolve();
                    };

                    xhr.send(file);
                });
            }
            selectedFiles = [];
            loadShelf();
        });

        async function loadShelf() {
            const shelfList = document.getElementById('shelfList');
            const shelfLoading = document.getElementById('shelfLoading');
            try {
                const res = await fetch(withToken('/api/books'));
                const books = await res.json();
                shelfLoading.style.display = 'none';
                shelfList.innerHTML = '';
                if (books.length === 0) {
                    shelfList.innerHTML = '<li style="text-align:center; color:#8b949e; font-size:13px; padding:12px;">手表书架暂无书籍</li>';
                    return;
                }
                for (let b of books) {
                    const li = document.createElement('li');
                    li.className = 'file-item';
                    const sizeKb = Math.round(b.size / 1024);
                    li.innerHTML = `
                        <div class="file-header">
                            <span class="file-name">${'$'}{b.name}</span>
                            <div style="display:flex;gap:6px;">
                                <a class="down-btn" href="${'$'}{withToken('/api/download?name=' + encodeURIComponent(b.name))}" download>导出</a>
                                <button class="del-btn" onclick="deleteBook('${'$'}{encodeURIComponent(b.name)}')">删除</button>
                            </div>
                        </div>
                        <div class="book-meta">${'$'}{sizeKb} KB</div>
                    `;
                    shelfList.appendChild(li);
                }
            } catch (e) {
                shelfLoading.innerText = '加载失败';
            }
        }

        async function deleteBook(encodedName) {
            const name = decodeURIComponent(encodedName);
            if (!confirm('确定要从手表中删除《' + name + '》吗？')) return;
            try {
                const res = await fetch(withToken('/api/books/delete?name=' + encodedName), { method: 'POST' });
                if (res.ok) {
                    loadShelf();
                } else {
                    alert('删除失败');
                }
            } catch (e) {
                alert('网络错误');
            }
        }

        window.addEventListener('DOMContentLoaded', loadShelf);
    </script>
</body>
</html>
""".trimIndent()
