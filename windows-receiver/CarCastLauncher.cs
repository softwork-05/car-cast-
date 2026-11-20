using System;
using System.Diagnostics;
using System.IO;
using System.Net.Sockets;
using System.Threading;

namespace CarCastMirror
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            string appDir = AppDomain.CurrentDomain.BaseDirectory;
            Process nodeProcess = null;

            if (!IsPortListening(8088))
            {
                string serverJs = FindServerScript(appDir);
                if (!string.IsNullOrEmpty(serverJs))
                {
                    string serverWorkingDir = Path.GetDirectoryName(serverJs);
                    ProcessStartInfo nodePsi = new ProcessStartInfo
                    {
                        FileName = "node.exe",
                        Arguments = "\"" + serverJs + "\"",
                        WorkingDirectory = serverWorkingDir,
                        CreateNoWindow = true,
                        UseShellExecute = false,
                        WindowStyle = ProcessWindowStyle.Hidden
                    };

                    try
                    {
                        nodeProcess = Process.Start(nodePsi);
                    }
                    catch
                    {
                        string[] nodePaths = new string[]
                        {
                            @"C:\Program Files\nodejs\node.exe",
                            @"C:\Program Files (x86)\nodejs\node.exe",
                            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"Programs\node\node.exe")
                        };
                        foreach (string np in nodePaths)
                        {
                            if (File.Exists(np))
                            {
                                nodePsi.FileName = np;
                                try { nodeProcess = Process.Start(nodePsi); break; } catch { }
                            }
                        }
                    }
                }
            }

            int attempts = 0;
            while (!IsPortListening(8088) && attempts < 25)
            {
                Thread.Sleep(200);
                attempts++;
            }

            string browserPath = FindAppBrowser();
            if (string.IsNullOrEmpty(browserPath))
            {
                Process.Start("http://localhost:8088");
                return;
            }

            string userDataDir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "CarCastMirror", "UserData"
            );
            try { Directory.CreateDirectory(userDataDir); } catch { }

            string arguments = string.Format(
                "--app=http://localhost:8088 --window-size=1280,820 --user-data-dir=\"{0}\"",
                userDataDir
            );

            ProcessStartInfo appPsi = new ProcessStartInfo
            {
                FileName = browserPath,
                Arguments = arguments,
                UseShellExecute = true
            };

            try
            {
                Process appProcess = Process.Start(appPsi);
                if (appProcess != null)
                {
                    appProcess.WaitForExit();
                }
            }
            catch
            {
                Process.Start("http://localhost:8088");
            }
            finally
            {
                if (nodeProcess != null && !nodeProcess.HasExited)
                {
                    try { nodeProcess.Kill(); } catch { }
                }
            }
        }

        static string FindServerScript(string baseDir)
        {
            string[] possiblePaths = new string[]
            {
                Path.Combine(baseDir, "server.js"),
                Path.Combine(baseDir, "windows-receiver", "server.js"),
                Path.Combine(baseDir, "CarCastMirror", "windows-receiver", "server.js"),
                Path.Combine(baseDir, "..", "windows-receiver", "server.js"),
                Path.Combine(baseDir, "..", "server.js")
            };
            foreach (string p in possiblePaths)
            {
                try
                {
                    string full = Path.GetFullPath(p);
                    if (File.Exists(full)) return full;
                }
                catch { }
            }
            return null;
        }

        static bool IsPortListening(int port)
        {
            try
            {
                using (TcpClient client = new TcpClient())
                {
                    IAsyncResult result = client.BeginConnect("127.0.0.1", port, null, null);
                    bool success = result.AsyncWaitHandle.WaitOne(150);
                    if (success && client.Connected)
                    {
                        client.EndConnect(result);
                        return true;
                    }
                }
            }
            catch { }
            return false;
        }

        static string FindAppBrowser()
        {
            string[] paths = new string[]
            {
                @"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
                @"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), @"Microsoft\Edge\Application\msedge.exe"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), @"Microsoft\Edge\Application\msedge.exe"),
                @"C:\Program Files\Google\Chrome\Application\chrome.exe",
                @"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"
            };
            foreach (string p in paths)
            {
                if (File.Exists(p)) return p;
            }
            return "msedge.exe";
        }
    }
}
