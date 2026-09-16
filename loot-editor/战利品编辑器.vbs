Set ws = CreateObject("Wscript.Shell")
ws.CurrentDirectory = CreateObject("Scripting.FileSystemObject").GetParentFolderName(WScript.ScriptFullName)
ws.Run "C:\Users\79662\.workbuddy-ai\binaries\python\envs\default\Scripts\python.exe app.py", 0, False