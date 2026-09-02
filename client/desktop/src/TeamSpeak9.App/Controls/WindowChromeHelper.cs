// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace TeamSpeak9.App.Controls;

/// <summary>
/// Keeps a maximized borderless window inside the work area and reports the real caption size.
/// </summary>
/// <remarks>
/// A window with <c>WindowStyle="None"</c> and <c>WindowState="Maximized"</c> covers the taskbar,
/// because Windows sizes it to the full monitor rect rather than the work area. Handling
/// <c>WM_GETMINMAXINFO</c> is the standard fix. Everything here is per-monitor aware, which
/// matters on mixed-DPI setups (the app manifest declares PerMonitorV2).
/// </remarks>
internal static class WindowChromeHelper
{
    private const int WM_GETMINMAXINFO = 0x0024;
    private const int MONITOR_DEFAULTTONEAREST = 0x00000002;

    /// <summary>Attaches the hook. Safe to call before the window is shown.</summary>
    public static void Attach(Window window)
    {
        ArgumentNullException.ThrowIfNull(window);

        if (new WindowInteropHelper(window).Handle != IntPtr.Zero)
        {
            Hook(window);
            return;
        }

        window.SourceInitialized += OnSourceInitialized;
    }

    private static void OnSourceInitialized(object? sender, EventArgs e)
    {
        if (sender is not Window window)
            return;

        window.SourceInitialized -= OnSourceInitialized;
        Hook(window);
    }

    private static void Hook(Window window)
    {
        var handle = new WindowInteropHelper(window).Handle;
        HwndSource.FromHwnd(handle)?.AddHook(WindowProc);
    }

    private static IntPtr WindowProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == WM_GETMINMAXINFO)
        {
            AdjustMaximizedBounds(hwnd, lParam);
            handled = true;
        }

        return IntPtr.Zero;
    }

    private static void AdjustMaximizedBounds(IntPtr hwnd, IntPtr lParam)
    {
        var monitor = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
        if (monitor == IntPtr.Zero)
            return;

        var info = new MONITORINFO { cbSize = Marshal.SizeOf<MONITORINFO>() };
        if (!GetMonitorInfo(monitor, ref info))
            return;

        var mmi = Marshal.PtrToStructure<MINMAXINFO>(lParam);

        // rcWork excludes the taskbar; both rects are in physical pixels relative to the
        // virtual desktop, so the position has to be made monitor-relative.
        mmi.ptMaxPosition.X = info.rcWork.Left - info.rcMonitor.Left;
        mmi.ptMaxPosition.Y = info.rcWork.Top - info.rcMonitor.Top;
        mmi.ptMaxSize.X = info.rcWork.Right - info.rcWork.Left;
        mmi.ptMaxSize.Y = info.rcWork.Bottom - info.rcWork.Top;

        // Without this the window cannot be dragged larger than the primary monitor on
        // multi-monitor setups.
        mmi.ptMaxTrackSize.X = mmi.ptMaxSize.X;
        mmi.ptMaxTrackSize.Y = mmi.ptMaxSize.Y;

        Marshal.StructureToPtr(mmi, lParam, fDeleteOld: true);
    }

    [DllImport("user32.dll")]
    private static extern IntPtr MonitorFromWindow(IntPtr hwnd, int flags);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetMonitorInfo(IntPtr hMonitor, ref MONITORINFO lpmi);

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT
    {
        public int X;
        public int Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct RECT
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MONITORINFO
    {
        public int cbSize;
        public RECT rcMonitor;
        public RECT rcWork;
        public int dwFlags;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MINMAXINFO
    {
        public POINT ptReserved;
        public POINT ptMaxSize;
        public POINT ptMaxPosition;
        public POINT ptMinTrackSize;
        public POINT ptMaxTrackSize;
    }
}
