const getFilesButton = document.getElementById('getFiles');
const deleteFileButton = document.getElementById('deleteFile');
const saveFileButton = document.getElementById('saveFile');
const saveFileAsButton = document.getElementById('saveFileAs');
const newFileNameInput = document.getElementById('newFileName');
const fileList = document.getElementById('fileList');
const fileContentNonEditable = document.getElementById('fileContentNonEditable');
const fileContentEditable = document.getElementById('fileContentEditable');

let selectedFile = null;
let Payload;

fetch('/profedit.proto').then(response => response.text()).then(protoDef => {
    const root = protobuf.parse(protoDef).root;
    Payload = root.lookupType("profedit.Payload");
});

const transformToEditableWorker = new Worker('transform-to-editable-worker.js');
const transformFromEditableWorker = new Worker('transform-from-editable-worker.js');

transformToEditableWorker.onmessage = event => {
    const editableJson = event.data;
    fileContentEditable.textContent = JSON.stringify(editableJson, null, 2);
    hljs.highlightElement(fileContentEditable);
};

transformFromEditableWorker.onmessage = event => {
    const nonEditableJson = event.data;
    fileContentNonEditable.textContent = JSON.stringify(nonEditableJson, null, 2);
    hljs.highlightElement(fileContentNonEditable);
};

const handleNonOkResponse = async (response) => {
    if (!response.ok) {
        const errorData = await response.json();
        alert(errorData.error);
    }
    return response;
};

const getFiles = async () => {
    const response = await fetch('/filelist').then(handleNonOkResponse);
    const files = await response.json();
    fileList.innerHTML = '';
    files.forEach(file => {
        const listItem = document.createElement('li');
        listItem.textContent = file;
        listItem.onclick = () => setSelectedFile(file);
        fileList.appendChild(listItem);
    });

    if (files.includes(selectedFile)) {
        setSelectedFile(selectedFile);
    } else if (files.length > 0) {
        setSelectedFile(files[0]);
    }
};

const setSelectedFile = async (file) => {
    selectedFile = file;

    Array.from(fileList.children).forEach(listItem => {
        listItem.classList.remove('active');
    });

    const selectedItem = Array.from(fileList.children).find(listItem => listItem.textContent === file);
    if (selectedItem) {
        selectedItem.classList.add('active');
    }

    const response = await fetch(`/files?filename=${selectedFile}`).then(handleNonOkResponse);
    const buffer = await response.arrayBuffer();
    const message = Payload.decode(new Uint8Array(buffer));
    const profileObj = Payload.toObject(message, { enums: String, defaults: true });

    fileContentNonEditable.textContent = JSON.stringify(profileObj, null, 2);
    hljs.highlightElement(fileContentNonEditable);
    transformToEditableWorker.postMessage(profileObj);
};

getFilesButton.onclick = getFiles;

deleteFileButton.onclick = async () => {
    if (!selectedFile) {
        alert('No file selected!');
        return;
    }
    const response = await fetch(`/files?filename=${selectedFile}`, { method: 'DELETE' }).then(handleNonOkResponse);
    if (response.ok) {
        alert(`Deleted ${selectedFile}`);
        selectedFile = null;
        getFiles();
    } else {
        alert('Failed to delete file');
    }
};

const saveChanges = async (filename) => {
    const messageObj = JSON.parse(fileContentNonEditable.textContent);
    const message = Payload.create(messageObj);
    const buffer = Payload.encode(message).finish();

    const response = await fetch(`/files?filename=${filename}`, {
        method: 'PUT',
        headers: {
            'Content-Type': 'application/x-protobuf'
        },
        body: buffer
    }).then(handleNonOkResponse);
    return response;
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
    }).then(handleNonOkResponse);

    if (response.ok) {
        alert(`Saved changes to ${selectedFile}`);
        getFiles();
    } else {
        alert('Failed to save file');
    }
};

saveFileAsButton.onclick = async () => {
    let newFileName = newFileNameInput.value;
    if (!newFileName) {
        alert('No file name provided!');
        return;
    }

    if (!newFileName.endsWith('.a7p')) {
        newFileName += '.a7p';
    }

    const response = await saveChanges(newFileName).then(handleNonOkResponse);
    if (response.ok) {
        alert(`Saved as ${newFileName}`);
        selectedFile = newFileName;
        getFiles();
    } else {
        alert('Failed to save file');
    }
};

getFiles();
