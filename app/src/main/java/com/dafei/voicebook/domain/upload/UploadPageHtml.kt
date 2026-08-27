package com.dafei.voicebook.domain.upload

object UploadPageHtml {
    val content: String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Voicebook 上传</title>
  <style>
    * { box-sizing: border-box; }
    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; max-width: 560px; margin: 40px auto; padding: 0 16px; color: #1a1a1a; }
    h1 { font-size: 1.4rem; margin-bottom: 8px; }
    .hint { color: #666; font-size: 0.9rem; margin-bottom: 24px; }
    .drop-zone { border: 2px dashed #aaa; border-radius: 12px; padding: 40px 20px; text-align: center; cursor: pointer; transition: border-color 0.2s, background 0.2s; }
    .drop-zone:hover, .drop-zone.dragover { border-color: #4a7cff; background: #f0f4ff; }
    input[type=file] { display: none; }
    button { margin-top: 16px; padding: 10px 24px; background: #4a7cff; color: #fff; border: none; border-radius: 8px; font-size: 1rem; cursor: pointer; }
    button:disabled { background: #aaa; cursor: not-allowed; }
    #results { margin-top: 24px; }
    .result-item { padding: 8px 12px; border-radius: 6px; margin-bottom: 6px; font-size: 0.9rem; }
    .success { background: #e8f5e9; color: #2e7d32; }
    .duplicate { background: #fff8e1; color: #f57f17; }
    .failed { background: #ffebee; color: #c62828; }
  </style>
</head>
<body>
  <h1>上传电子书</h1>
  <p class="hint">仅限同一 WiFi 局域网内使用。仅支持 EPUB 格式，可多选或拖拽上传。</p>
  <div class="drop-zone" id="dropZone">
    <p>点击选择文件，或将文件拖拽到此处</p>
    <input type="file" id="fileInput" multiple accept=".epub,application/epub+zip">
  </div>
  <button id="uploadBtn" disabled>开始上传</button>
  <div id="results"></div>
  <script>
    const dropZone = document.getElementById('dropZone');
    const fileInput = document.getElementById('fileInput');
    const uploadBtn = document.getElementById('uploadBtn');
    const results = document.getElementById('results');
    let selectedFiles = [];

    dropZone.addEventListener('click', () => fileInput.click());
    dropZone.addEventListener('dragover', e => { e.preventDefault(); dropZone.classList.add('dragover'); });
    dropZone.addEventListener('dragleave', () => dropZone.classList.remove('dragover'));
    dropZone.addEventListener('drop', e => {
      e.preventDefault();
      dropZone.classList.remove('dragover');
      selectedFiles = Array.from(e.dataTransfer.files);
      updateBtn();
    });
    fileInput.addEventListener('change', () => {
      selectedFiles = Array.from(fileInput.files);
      updateBtn();
    });

    function updateBtn() {
      uploadBtn.disabled = selectedFiles.length === 0;
      dropZone.querySelector('p').textContent = selectedFiles.length
        ? '已选择 ' + selectedFiles.length + ' 个文件'
        : '点击选择文件，或将文件拖拽到此处';
    }

    uploadBtn.addEventListener('click', async () => {
      uploadBtn.disabled = true;
      results.innerHTML = '';
      for (const file of selectedFiles) {
        const formData = new FormData();
        formData.append('file', file);
        formData.append('filename', file.name);
        try {
          const resp = await fetch('/upload', { method: 'POST', body: formData });
          const data = await resp.json();
          const cls = data.status === 'started' ? 'success' : data.status === 'duplicate' ? 'duplicate' : 'failed';
          const div = document.createElement('div');
          div.className = 'result-item ' + cls;
          div.textContent = file.name + ' — ' + data.message;
          results.appendChild(div);
        } catch (e) {
          const div = document.createElement('div');
          div.className = 'result-item failed';
          div.textContent = file.name + ' — 上传失败';
          results.appendChild(div);
        }
      }
      selectedFiles = [];
      fileInput.value = '';
      updateBtn();
    });
  </script>
</body>
</html>
    """.trimIndent()
}
