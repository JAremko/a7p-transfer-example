const getFilesButton = document.getElementById('getFiles');
const deleteFileButton = document.getElementById('deleteFile');
const saveFileButton = document.getElementById('saveFile');
const saveFileAsButton = document.getElementById('saveFileAs');
const newFileNameInput = document.getElementById('newFileName');
const fileList = document.getElementById('fileList');
const fileContentNonEditable = document.getElementById('fileContentNonEditable');
const fileContentEditable = document.getElementById('fileContentEditable');

let selectedFile = null;

// Let's define your two web worker pipelines here
const transformToEditableWorker = new Worker('transform-to-editable-worker.js');
const transformFromEditableWorker = new Worker('transform-from-editable-worker.js');

const getFiles = async () => {
  const response = await fetch('/filelist');
  const files = await response.json();
  fileList.innerHTML = '';
  for (const file of files) {
    const listItem = document.createElement('li');
    listItem.textContent = file;
    listItem.onclick = async () => {
      setSelectedFile(file);
    };
    fileList.appendChild(listItem);
  }
  // Re-select the current file, if it's still in the list
  if (files.includes(selectedFile)) {
    setSelectedFile(selectedFile);
  } else if (files.length > 0) {
    setSelectedFile(files[0]);
  }
};

const setSelectedFile = async (file) => {
  selectedFile = file;
  const response = await fetch(`/files?filename=${selectedFile}`);
  const data = await response.json();
  fileContentNonEditable.textContent = JSON.stringify(data, null, 2); // Set text content
  hljs.highlightElement(fileContentNonEditable); // Apply highlighting

  // Only post the message once we have the data
  transformToEditableWorker.postMessage(data);
  transformToEditableWorker.onmessage = event => {
    const editableContent = event.data;
    fileContentEditable.textContent = JSON.stringify(editableContent, null, 2); // Set text content
    hljs.highlightElement(fileContentEditable); // Apply highlighting
  };

  // Highlight active file in the list
  for (let li of fileList.children) {
    if (li.textContent === selectedFile) {
      li.classList.add('active');
    } else {
      li.classList.remove('active');
    }
  }
};

// Update non-editable content when editable content changes
fileContentEditable.oninput = () => {
  const editableContentJson = JSON.parse(fileContentEditable.textContent);

  // Using the transformFromEditableWorker web worker
  transformFromEditableWorker.postMessage(editableContentJson);
  transformFromEditableWorker.onmessage = event => {
    const nonEditableContent = event.data;
    fileContentNonEditable.textContent = JSON.stringify(nonEditableContent, null, 2); // Set text content
    hljs.highlightElement(fileContentNonEditable); // Apply highlighting
  };
};

getFilesButton.onclick = getFiles;

deleteFileButton.onclick = async () => {
  if (!selectedFile) {
    alert('No file selected!');
    return;
  }
  const response = await fetch(`/files?filename=${selectedFile}`, { method: 'DELETE' });
  if (response.ok) {
    alert(`Deleted ${selectedFile}`);
    selectedFile = null;
    getFiles();
  } else {
    alert('Failed to delete file');
  }
};

saveFileButton.onclick = async () => {
  if (selectedFile === null) {
    alert('No file selected!');
    return;
  }

  let fileContentJson;
  try {
    fileContentJson = JSON.parse(fileContentNonEditable.textContent);
  } catch (e) {
    alert('Invalid JSON content');
    return;
  }

  const response = await fetch(`/files?filename=${selectedFile}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ content: fileContentJson })
  });
  if (response.ok) {
    alert(`Saved changes to ${selectedFile}`);
    getFiles(); // Get the files but will not reset the selection
  } else {
    alert('Failed to save file');
  }
};

saveFileAsButton.onclick = async () => {
  const newFileName = newFileNameInput.value;
  if (!newFileName) {
    alert('No file name provided!');
    return;
  }

  let fileContentJson;
  try {
    fileContentJson = JSON.parse(fileContentNonEditable.textContent);
  } catch (e) {
    alert('Invalid JSON content');
    return;
  }

  const response = await fetch(`/files?filename=${newFileName}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ content: fileContentJson })
  });
  if (response.ok) {
    alert(`Saved as ${newFileName}`);
    selectedFile = newFileName; // Select the new file
    getFiles(); // Refresh the file list
  } else {
    alert('Failed to save file');
  }
};

// Fetch files on page load
getFiles();
