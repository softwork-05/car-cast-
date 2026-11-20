const { app, BrowserWindow } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const serverManager = require('./server.js');

const logFile = path.join(os.tmpdir(), 'carcast_app.log');
function log(msg) {
    try { fs.appendFileSync(logFile, `[${new Date().toISOString()}] ${msg}\n`); } catch (_) {}
}

log('Electron main script entered');

// 1. Enforce strict single-instance lock across Windows
const hasSingleInstanceLock = app.requestSingleInstanceLock();
if (!hasSingleInstanceLock) {
    log('Duplicate instance detected. Exiting.');
    app.quit();
    process.exit(0);
}

log('Acquired single instance lock');

let mainWindow = null;

// If a second instance is launched (e.g. user double-clicked exe again), focus the existing window
app.on('second-instance', (event, commandLine, workingDirectory) => {
    log('Second instance invoked, focusing existing window');
    if (mainWindow) {
        if (mainWindow.isMinimized()) mainWindow.restore();
        mainWindow.show();
        mainWindow.focus();
    }
});

function createWindow() {
    if (mainWindow) {
        mainWindow.focus();
        return;
    }

    log('Creating main BrowserWindow');
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

    const targetUrl = `http://localhost:${serverManager.HTTP_PORT}`;
    log('Loading URL in BrowserWindow: ' + targetUrl);
    mainWindow.loadURL(targetUrl);

    mainWindow.on('closed', () => {
        log('Main window closed');
        mainWindow = null;
    });
}

let serverStarted = false;
app.whenReady().then(() => {
    log('app.whenReady fired');
    if (!serverStarted) {
        serverStarted = true;
        serverManager.startServer(() => {
            log('serverManager.startServer callback fired');
            createWindow();
        });
    }

    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
});

app.on('window-all-closed', () => {
    log('app.window-all-closed fired');
    serverManager.stopServer(() => {
        app.quit();
    });
});

app.on('before-quit', () => {
    log('app.before-quit fired');
    serverManager.stopServer();
});
