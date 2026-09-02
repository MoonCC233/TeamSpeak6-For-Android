// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.App.Controls;
using TeamSpeak9.Core.Settings;

namespace TeamSpeak9.App;

public partial class MainWindow : ShellWindow
{
    public MainWindow()
        : this(null)
    {
    }

    internal MainWindow(AppPaths? paths)
    {
        InitializeComponent();

        StatusText.Text = paths is null
            ? "正在初始化…"
            : $"配置目录：{paths.Root}";
    }
}
