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
                Path.Combine(appDir, "CarCastMirror.exe")
            };

            foreach (string ap in appPaths)
            {
                try
                {
                    string fullPath = Path.GetFullPath(ap);
                    // Avoid launching self in an infinite loop
                    if (string.Equals(fullPath, System.Reflection.Assembly.GetExecutingAssembly().Location, StringComparison.OrdinalIgnoreCase))
                    {
                        continue;
                    }

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
        }
    }
}
