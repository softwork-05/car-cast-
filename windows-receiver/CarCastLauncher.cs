using System;
using System.Diagnostics;
using System.IO;

namespace CarCastMirror
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            string appDir = AppDomain.CurrentDomain.BaseDirectory;

            string[] appPaths = new string[]
            {
                Path.Combine(appDir, "CarCastMirror-Windows-App", "CarCastMirror.exe"),
                Path.Combine(appDir, "..", "CarCastMirror-Windows-App", "CarCastMirror.exe"),
                Path.Combine(appDir, "dist-app", "CarCastMirror.exe"),
                Path.Combine(appDir, "windows-receiver", "dist-app", "CarCastMirror.exe"),
                Path.Combine(appDir, "windows-receiver", "node_modules", "electron", "dist", "electron.exe")
            };

            foreach (string ap in appPaths)
            {
                try
                {
                    string fullPath = Path.GetFullPath(ap);
                    if (File.Exists(fullPath))
                    {
                        ProcessStartInfo psi = new ProcessStartInfo
                        {
                            FileName = fullPath,
                            WorkingDirectory = Path.GetDirectoryName(fullPath),
                            UseShellExecute = true
                        };
                        Process.Start(psi);
                        return;
                    }
                }
                catch { }
            }

            // Fallback: If bundled app is somehow not found, open local portal
            try
            {
                Process.Start("http://localhost:8088");
            }
            catch { }
        }
    }
}
