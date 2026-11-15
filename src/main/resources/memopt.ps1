$ErrorActionPreference = 'Continue'
try {
  if (-not ('WinMemClear' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
public static class WinMemClear {
  [StructLayout(LayoutKind.Sequential, Pack = 4)] public struct TP { public uint Count; public long Luid; public uint Attr; }
  [StructLayout(LayoutKind.Sequential)] public struct PI {
    public uint cb;
    public ulong CommitTotal; public ulong CommitLimit; public ulong CommitPeak;
    public ulong PhysicalTotal; public ulong PhysicalAvailable; public ulong SystemCache;
    public ulong KernelTotal; public ulong KernelPaged; public ulong KernelNonPaged;
    public ulong PageSize;
    public uint HandleCount; public uint ProcessCount; public uint ThreadCount;
  }
  [DllImport("kernel32.dll")] public static extern IntPtr GetCurrentProcess();
  [DllImport("advapi32.dll", SetLastError = true)] public static extern bool OpenProcessToken(IntPtr h, uint a, out IntPtr t);
  [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)] public static extern bool LookupPrivilegeValue(string s, string n, out long l);
  [DllImport("advapi32.dll", SetLastError = true)] public static extern bool AdjustTokenPrivileges(IntPtr t, bool d, ref TP n, int len, IntPtr prev, IntPtr ret);
  [DllImport("ntdll.dll")] public static extern int NtSetSystemInformation(int cls, IntPtr info, int len);
  [DllImport("kernel32.dll", SetLastError = true)] public static extern bool CloseHandle(IntPtr h);
  [DllImport("psapi.dll", SetLastError = true)] public static extern bool GetPerformanceInfo(ref PI p, uint cb);
  [DllImport("kernel32.dll", SetLastError = true)] public static extern IntPtr VirtualAlloc(IntPtr a, UIntPtr s, uint type, uint prot);
  [DllImport("kernel32.dll", SetLastError = true)] public static extern bool VirtualFree(IntPtr a, UIntPtr s, uint type);
  [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
  public static extern IntPtr CreateFile(string n, uint acc, uint share, IntPtr sa, uint disp, uint flags, IntPtr tmpl);
  [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
  public static extern IntPtr CreateFileMapping(IntPtr h, IntPtr sa, uint prot, uint maxHi, uint maxLo, string name);
  [DllImport("kernel32.dll", SetLastError = true)]
  public static extern IntPtr MapViewOfFile(IntPtr h, uint acc, uint offHi, uint offLo, UIntPtr len);
  [DllImport("kernel32.dll", SetLastError = true)] public static extern bool UnmapViewOfFile(IntPtr p);
  [DllImport("kernel32.dll", SetLastError = true)] public static extern bool SetEndOfFile(IntPtr h);
  [DllImport("kernel32.dll", SetLastError = true)] public static extern bool SetFilePointerEx(IntPtr h, long dist, IntPtr old, uint method);

  // Task Manager "Cached" = system cache pages (standby + modified)
  public static ulong CachedKB() {
    PI p = new PI();
    p.cb = (uint)Marshal.SizeOf(typeof(PI));
    if (!GetPerformanceInfo(ref p, p.cb)) return 0;
    return p.SystemCache * p.PageSize / 1024;
  }

  // How much memory we may hold to squeeze the standby list out:
  // nearly all available pages (free + standby), minus a safety reserve,
  // capped by free disk space of the temp file (mapped file needs backing size).
  public static ulong FlushTargetKB() {
    PI p = new PI();
    p.cb = (uint)Marshal.SizeOf(typeof(PI));
    if (!GetPerformanceInfo(ref p, p.cb)) return 0;
    ulong availKB = p.PhysicalAvailable * p.PageSize / 1024;
    // hold almost everything: leave only 256 MB so standby is fully squeezed out
    ulong reserveKB = 262144UL;
    ulong t = availKB > reserveKB ? availKB - reserveKB : 0;
    long freeKB = 0;
    foreach (DriveInfo d in DriveInfo.GetDrives()) {
      try {
        if (!d.IsReady) continue;
        long f = d.AvailableFreeSpace / 1024;
        if (f > freeKB) freeKB = f;
      } catch { }
    }
    if (freeKB > 2097152L) {
      ulong diskCapKB = (ulong)freeKB - 2097152UL;
      if (diskCapKB < t) t = diskCapKB;
    } else {
      t = 0;
    }
    return t;
  }

  // Primary path (works without admin): memory-map a huge temp file and touch it.
  // File-mapped pages are NOT commit-charged, so this can consume ALL free physical
  // pages and force Windows to discard standby (cached) pages. Truncating the file
  // then throws every page away - they return to the free list, not the cache.
  public static ulong SqueezeByFileMap(ulong target) {
    List<string> paths = new List<string>();
    long bestFree = 0;
    string bestRoot = null;
    foreach (DriveInfo d in DriveInfo.GetDrives()) {
      try {
        if (!d.IsReady) continue;
        if (d.AvailableFreeSpace > bestFree) { bestFree = d.AvailableFreeSpace; bestRoot = d.RootDirectory.FullName; }
      } catch { }
    }
    if (bestRoot != null) paths.Add(Path.Combine(bestRoot, "yhcl-cache-flush.tmp"));
    try { paths.Add(Path.Combine(Path.GetTempPath(), "yhcl-cache-flush.tmp")); } catch { }
    foreach (string path in paths) {
      IntPtr h = CreateFile(path, 0xC0010000, 1, IntPtr.Zero, 2, 0x00000280, IntPtr.Zero);
      if (h == new IntPtr(-1)) continue;
      IntPtr map = IntPtr.Zero, view = IntPtr.Zero;
      try {
        if (!SetFilePointerEx(h, (long)target, IntPtr.Zero, 0) || !SetEndOfFile(h)) continue;
        map = CreateFileMapping(h, IntPtr.Zero, 0x04, 0, 0, null);
        if (map == IntPtr.Zero) continue;
        view = MapViewOfFile(map, 0xF001F, 0, 0, (UIntPtr)target);
        if (view == IntPtr.Zero) continue;
        for (ulong o = 0; o < target; o += 4096) Marshal.WriteInt64(new IntPtr(view.ToInt64() + (long)o), 0, 1);
        return target;
      } finally {
        if (view != IntPtr.Zero) UnmapViewOfFile(view);
        if (map != IntPtr.Zero) CloseHandle(map);
        // truncation discards every cache page of the file (dirty pages included)
        SetFilePointerEx(h, 0, IntPtr.Zero, 0);
        SetEndOfFile(h);
        CloseHandle(h);
        try { File.Delete(path); } catch { }
      }
    }
    return 0;
  }

  // Fallback (no disk available): hold private allocations to squeeze what we can
  public static ulong SqueezeByAlloc(ulong target) {
    List<IntPtr> held = new List<IntPtr>();
    ulong chunk = 268435456UL;
    ulong done = 0;
    try {
      while (done < target) {
        ulong size = target - done < chunk ? target - done : chunk;
        IntPtr p = VirtualAlloc(IntPtr.Zero, (UIntPtr)size, 0x3000, 0x04);
        if (p == IntPtr.Zero) break;
        for (ulong o = 0; o < size; o += 4096) Marshal.WriteInt64(new IntPtr(p.ToInt64() + (long)o), 0, 1);
        held.Add(p);
        done += size;
      }
    } finally {
      foreach (IntPtr p in held) VirtualFree(p, UIntPtr.Zero, 0x8000);
    }
    return done;
  }

  // Admin path: flush modified pages to standby, then drop the standby list
  public static int PurgeCache() {
    IntPtr token = IntPtr.Zero;
    if (!OpenProcessToken(GetCurrentProcess(), 0x28, out token)) return -1;
    try {
      long luid;
      if (!LookupPrivilegeValue(null, "SeProfileSingleProcessPrivilege", out luid)) return -2;
      TP tp = new TP();
      tp.Count = 1; tp.Luid = luid; tp.Attr = 2;
      if (!AdjustTokenPrivileges(token, false, ref tp, 0, IntPtr.Zero, IntPtr.Zero)) return -3;
      IntPtr buf = Marshal.AllocHGlobal(4);
      try {
        Marshal.WriteInt32(buf, 3);
        NtSetSystemInformation(80, buf, 4);
        Marshal.WriteInt32(buf, 4);
        return NtSetSystemInformation(80, buf, 4);
      } finally { Marshal.FreeHGlobal(buf); }
    } finally { CloseHandle(token); }
  }
}
'@
  }
  $cb0 = [WinMemClear]::CachedKB()
  $how = 'skip'
  $st = [WinMemClear]::PurgeCache()
  if ($st -eq 0) {
    $how = 'nt'
    Start-Sleep -Milliseconds 400
  } else {
    $rounds = 0
    $totalDone = [uint64]0
    for ($r = 0; $r -lt 5; $r++) {
      $cur = [WinMemClear]::CachedKB()
      if ($cur -lt 262144) { break }
      $tgKB = [WinMemClear]::FlushTargetKB()
      if ($tgKB -le 262144) { break }
      $done = [WinMemClear]::SqueezeByFileMap($tgKB * 1024)
      if ($done -gt 0) { $how = 'map' } else {
        $done = [WinMemClear]::SqueezeByAlloc($tgKB * 1024)
        if ($done -gt 0) { $how = 'alloc' }
      }
      if ($done -eq 0) { break }
      $rounds++
      $totalDone += $done
      Start-Sleep -Milliseconds 300
      if ([WinMemClear]::CachedKB() -ge $cur) { break }
    }
    if ($rounds -gt 0) {
      Write-Output ('FLUSH ROUNDS={0} DONE_MB={1}' -f $rounds, ($totalDone / 1MB))
    }
  }
  $cb1 = [WinMemClear]::CachedKB()
  Write-Output ('RESULT STATUS={0:X8} HOW={1} CACHE_BEFORE_KB={2} CACHE_AFTER_KB={3}' -f $st, $how, $cb0, $cb1)
} catch {
  Write-Output ('RESULT ERROR=' + $_.Exception.Message)
}
