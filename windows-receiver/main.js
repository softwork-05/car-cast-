const { app, BrowserWindow } = require('electron');
const path = require('path');
const http = require('http');

let mainWindow = null;

// Start internal streaming receiver server
function startServer(onReady) {
    const checkReq = http.get('http://127.0.0.1:8088/', (res) => {
        // Server already listening
        onReady();
    });
    checkReq.on('error', () => {
        // Not running yet, start server.js
        try {
            require('./server.js');
        } catch (e) {
            console.error('Failed to start server.js:', e);
        }
        setTimeout(onReady, 600);
    });
    checkReq.setTimeout(300, () => {
        checkReq.destroy();
        try { require('./server.js'); } catch (_) {}
        setTimeout(onReady, 600);
    });
}

function createWindow() {
    mainWindow = new BrowserWindow({
        width: 1280,
        height: 820,
        minWidth: 800,
        minHeight: 600,
        title: "CarCast Mirror — Windows PC Receiver",
        backgroundColor: "#0D1117",
        autoHideMenuBar: true,
        webPreferences: {
            nodeIntegration: false,
            contextIsolation: true
        }
    });

    mainWindow.loadURL('http://localhost:8088');

    mainWindow.on('closed', () => {
        mainWindow = null;
        app.quit();
    });
}

app.whenReady().then(() => {
    startServer(() => {
        createWindow();
    });

    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
});

app.on('window-all-closed', () => {
    app.quit();
});
