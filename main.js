const getFilesButton = document.getElementById('getFiles');
const deleteFileButton = document.getElementById('deleteFile');
const saveFileButton = document.getElementById('saveFile');
const saveFileAsButton = document.getElementById('saveFileAs');
const newFileNameInput = document.getElementById('newFileName');
const fileList = document.getElementById('fileList');
const fileContent = document.getElementById('fileContent');

let selectedFile = null;

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
  // Select first file by default
  if (files.length > 0) {
    setSelectedFile(files[0]);
  }
};

const setSelectedFile = async (file) => {
  selectedFile = file;
  const response = await fetch(`/files?filename=${selectedFile}`);
  const data = await response.json();
  fileContent.textContent = JSON.stringify(data, null, 2); // Set text content
  hljs.highlightElement(fileContent); // Apply highlighting

  // Highlight active file in the list
  for (let li of fileList.children) {
    if (li.textContent === selectedFile) {
      li.classList.add('active');
    } else {
      li.classList.remove('active');
    }
  }
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
    fileContentJson = JSON.parse(fileContent.textContent);
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
    getFiles();
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
    fileContentJson = JSON.parse(fileContent.textContent);
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
    getFiles();
  } else {
    alert('Failed to save file');
  }
};

// Call getFiles on page load
window.onload = getFiles;
