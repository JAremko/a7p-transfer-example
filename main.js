const getFilesButton = document.getElementById('getFiles');
const deleteFileButton = document.getElementById('deleteFile');
const saveFileButton = document.getElementById('saveFile');
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
      selectedFile = file;
      const response = await fetch(`/files?filename=${selectedFile}`);
      const data = await response.json();
      fileContent.value = JSON.stringify(data, null, 2); // Display formatted JSON string
    };
    fileList.appendChild(listItem);
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

    // Parse the JSON from the text area
    let fileContentJson;
    try {
        fileContentJson = JSON.parse(fileContent.value);
    } catch (e) {
        alert('Invalid JSON content');
        return;
    }

    const response = await fetch(`/files?filename=${selectedFile}`, {
        method: 'PUT',
        headers: {
            'Content-Type': 'application/json'
        },
        // Send the parsed JSON, not as a string, but as an actual JSON object
        body: JSON.stringify({ content: fileContentJson })
    });
    if (response.ok) {
        alert(`Saved changes to ${selectedFile}`);
        getFiles();
    } else {
        alert('Failed to save file');
    }
};

// Call getFiles on page load
window.onload = getFiles;
