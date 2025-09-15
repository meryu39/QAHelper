import subprocess
import re
import pyperclip
import tkinter as tk
from tkinter import ttk, messagebox, scrolledtext
import time

# --- 타이머 관련 전역 변수 ---
is_recording = False
start_time = None

# ----------------------------
# ADB 관련 함수
# ----------------------------
def get_connected_devices():
    """연결된 ADB 디바이스 목록을 가져옵니다."""
    try:
        # adb-devices-parser 라이브러리가 없으므로 직접 파싱합니다.
        result = run_adb("adb devices", use_device_arg=False) # 이 함수 자체는 -s 옵션 없이 실행
        lines = result.strip().splitlines()
        devices = []
        for line in lines[1:]: # 첫 줄("List of devices attached")은 제외
            if "device" in line and not line.startswith("*"):
                devices.append(line.split()[0])
        return devices
    except Exception as e:
        log_text.insert(tk.END, f"[ERROR] 기기 목록 조회 실패: {e}\n")
        return []

def run_adb(command, device_id=None, use_device_arg=True):
    """선택된 디바이스에 ADB 명령어를 실행하고 결과를 반환하는 헬퍼 함수"""
    if use_device_arg:
        if not device_id or device_id == "연결된 기기 없음":
            messagebox.showerror("오류", "타겟 기기가 선택되지 않았습니다.\n기기 목록을 새로고침하고 선택해주세요.")
            return "[ERROR] 타겟 기기 없음"
        
        # adb 명령어에 타겟 디바이스(-s) 옵션 추가
        if command.strip().startswith("adb"):
            # 'adb devices' 같은 전체 명령어가 들어올 경우
            command = command.replace("adb", f"adb -s {device_id}", 1)
        else: 
            # 'shell ...' 같은 부분 명령어만 들어올 경우
            command = f"adb -s {device_id} {command}"

    try:
        process = subprocess.Popen(
            command,
            shell=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="ignore"
        )
        stdout, stderr = process.communicate()
        if stderr:
            return stdout.strip() + "\n[ERROR] " + stderr.strip()
        return stdout.strip()
    except Exception as e:
        return f"[ERROR] ADB 실행 중 문제 발생: {e}"

def get_foreground_package(device_id):
    """현재 화면의 포그라운드 앱 패키지 이름을 가져오는 함수"""
    output = run_adb("shell dumpsys window", device_id=device_id)
    for line in output.splitlines():
        if "mCurrentFocus" in line or "mFocusedApp" in line:
            match = re.search(r" ([a-zA-Z0-9_.]+)/", line)
            if match:
                return match.group(1)
    return None

# ----------------------------
# com.example.qahelper 앱 제어 함수
# ----------------------------
def start_qahelper_recording(package_name, device_id):
    log_text.insert(tk.END, f"[{device_id}] {package_name} 앱에 녹화 시작 명령 전송...\n")
    log_text.see(tk.END)
    action = f"{package_name}.action.START"
    receiver = f"{package_name}/.CommandBroadcastReceiver"
    command = f"shell am broadcast -a {action} -n {receiver}"
    result = run_adb(command, device_id=device_id)
    log_text.insert(tk.END, f"[RESULT] {result}\n"); log_text.see(tk.END)

def stop_qahelper_recording(package_name, device_id):
    log_text.insert(tk.END, f"[{device_id}] {package_name} 앱에 녹화 종료 명령 전송...\n")
    log_text.see(tk.END)
    action = f"{package_name}.action.STOP"
    receiver = f"{package_name}/.CommandBroadcastReceiver"
    command = f"shell am broadcast -a {action} -n {receiver}"
    result = run_adb(command, device_id=device_id)
    log_text.insert(tk.END, f"[RESULT] {result}\n"); log_text.see(tk.END)
    log_text.insert(tk.END, "[WAIT] 녹화 파일 저장을 위해 3초 대기...\n"); log_text.see(tk.END)
    root.after(3000, lambda: pull_latest_video(package_name, device_id))

def pull_latest_video(package_name, device_id):
    log_text.insert(tk.END, f"[{device_id}] 가장 최근 녹화된 파일 PC로 다운로드 시작...\n")
    log_text.see(tk.END)
    device_dir = f"/sdcard/Android/data/{package_name}/files/Movies"
    find_command = f'shell "ls -t {device_dir}/*.mp4 | head -n 1"'
    latest_file_path = run_adb(find_command, device_id=device_id)

    if not latest_file_path or "[ERROR]" in latest_file_path or "No such file or directory" in latest_file_path:
        log_text.insert(tk.END, f"[ERROR] 녹화된 파일을 찾을 수 없습니다. 경로: {device_dir}\n")
        log_text.see(tk.END)
        messagebox.showerror("오류", f"녹화된 파일을 찾을 수 없습니다.\n경로: {device_dir}\nADB 연결 및 파일 저장 위치를 확인하세요.")
        return

    filename = latest_file_path.split('/')[-1]
    pull_command = f"pull \"{latest_file_path}\"" # 경로에 공백이 있을 수 있으므로 따옴표 추가
    result = run_adb(pull_command, device_id=device_id)
    log_text.insert(tk.END, f"[INFO] 다운로드 완료: {filename}\n")
    log_text.insert(tk.END, f"[RESULT] {result}\n"); log_text.see(tk.END)
    messagebox.showinfo("성공", f"녹화 파일 다운로드 완료!\n{filename}")

# ----------------------------
# 앱 초기화/재실행
# ----------------------------
def reset_app(package_name, wait_time, device_id):
    log_text.insert(tk.END, f"[{device_id}] {package_name} 앱 초기화 시작...\n")
    log_text.see(tk.END)
    def step1():
        log_text.insert(tk.END, "[STEP1] 앱 강제 종료\n"); log_text.see(tk.END)
        run_adb(f"shell am force-stop {package_name}", device_id=device_id)
        root.after(500, step2)
    def step2():
        log_text.insert(tk.END, "[STEP2] 앱 데이터 삭제\n"); log_text.see(tk.END)
        run_adb(f"shell pm clear {package_name}", device_id=device_id)
        root.after(500, step3)
    def step3():
        log_text.insert(tk.END, f"[WAIT] 데이터 삭제 완료 대기 ({wait_time}초)\n"); log_text.see(tk.END)
        root.after(wait_time * 1000, step4)
    def step4():
        log_text.insert(tk.END, "[STEP3] 앱 재실행\n"); log_text.see(tk.END)
        output = run_adb(f"shell cmd package resolve-activity --brief {package_name}", device_id=device_id)
        activity_line = output.splitlines()[-1] if output else None
        if not activity_line:
            log_text.insert(tk.END, f"[ERROR] {package_name} Activity 확인 실패\n"); log_text.see(tk.END)
            return
        run_adb(f"shell am start -n {activity_line}", device_id=device_id)
        log_text.insert(tk.END, f"[INFO] {package_name} 앱 초기화 완료!\n"); log_text.see(tk.END)
    step1()

def relaunch_app(package_name, device_id):
    log_text.insert(tk.END, f"[{device_id}] {package_name} 앱 재실행 시작...\n"); log_text.see(tk.END)
    def step1():
        log_text.insert(tk.END, "[STEP1] 앱 강제 종료\n"); log_text.see(tk.END)
        run_adb(f"shell am force-stop {package_name}", device_id=device_id)
        root.after(500, step2)
    def step2():
        log_text.insert(tk.END, "[STEP2] 앱 실행\n"); log_text.see(tk.END)
        output = run_adb(f"shell cmd package resolve-activity --brief {package_name}", device_id=device_id)
        activity_line = output.splitlines()[-1] if output else None
        if not activity_line:
            log_text.insert(tk.END, f"[ERROR] {package_name} Activity 확인 실패\n"); log_text.see(tk.END)
            return
        run_adb(f"shell am start -n {activity_line}", device_id=device_id)
        log_text.insert(tk.END, f"[INFO] {package_name} 앱 재실행 완료!\n"); log_text.see(tk.END)
    step1()

# ----------------------------
# Tkinter GUI 함수
# ----------------------------
def refresh_device_list():
    """기기 목록을 새로고침하여 드롭다운 메뉴에 표시합니다."""
    devices = get_connected_devices()
    menu = device_menu["menu"]
    menu.delete(0, "end")
    if devices:
        for device in devices:
            menu.add_command(label=device, command=lambda value=device: selected_device.set(value))
        selected_device.set(devices[0])
        log_text.insert(tk.END, f"[INFO] 연결된 기기: {', '.join(devices)}\n")
    else:
        selected_device.set("연결된 기기 없음")
        log_text.insert(tk.END, "[WARN] 연결된 기기가 없습니다.\n")
    log_text.see(tk.END)

def update_timer():
    if is_recording:
        elapsed_time = time.time() - start_time
        timer_str = time.strftime('%H:%M:%S', time.gmtime(elapsed_time))
        timer_label.config(text=f"녹화 시간: {timer_str}")
        root.after(1000, update_timer)

def check_app():
    device_id = selected_device.get()
    pkg = get_foreground_package(device_id)
    if pkg:
        pkg_label.config(text=pkg)
        pyperclip.copy(pkg)
        log_text.insert(tk.END, f"[{device_id}] 현재 앱: {pkg} (클립보드 복사됨)\n")
        log_text.see(tk.END)
    else:
        messagebox.showerror("오류", "실행 중인 앱 패키지를 찾을 수 없습니다.")

def init_app():
    device_id = selected_device.get()
    pkg = pkg_label.cget("text")
    if pkg and pkg != "패키지명 없음":
        reset_app(pkg, 3, device_id)
    else:
        messagebox.showwarning("경고", "먼저 앱 패키지를 확인하세요.")

def relaunch():
    device_id = selected_device.get()
    pkg = pkg_label.cget("text")
    if pkg and pkg != "패키지명 없음":
        relaunch_app(pkg, device_id)
    else:
        messagebox.showwarning("경고", "먼저 앱 패키지를 확인하세요.")

def start_recording_wrapper():
    global is_recording, start_time
    if is_recording:
        messagebox.showwarning("경고", "이미 녹화가 진행 중입니다.")
        return
    
    device_id = selected_device.get()
    is_recording = True
    start_time = time.time()
    update_timer()
    start_qahelper_recording("com.example.qahelper", device_id)

def stop_recording_wrapper():
    global is_recording
    if not is_recording:
        messagebox.showwarning("경고", "녹화 중이 아닙니다.")
        return
        
    is_recording = False
    timer_label.config(text="녹화 시간: 00:00:00")
    device_id = selected_device.get()
    stop_qahelper_recording("com.example.qahelper", device_id)

def clear_log():
    log_text.delete("1.0", tk.END)

# ----------------------------
# GUI 구성
# ----------------------------
root = tk.Tk()
root.title("QA Helper GUI")
root.geometry("600x620")

# ▼▼▼ [추가됨] 디바이스 선택 프레임 ▼▼▼
device_frame = tk.Frame(root)
device_frame.pack(pady=(10, 0))
tk.Label(device_frame, text="타겟 기기:", font=("Arial", 10)).pack(side=tk.LEFT, padx=5)
selected_device = tk.StringVar()
# OptionMenu 대신 ttk.OptionMenu를 사용하여 더 나은 스타일링 제공
device_menu = ttk.OptionMenu(device_frame, selected_device, "연결된 기기 없음")
device_menu.config(width=20)
device_menu.pack(side=tk.LEFT, padx=5)
btn_refresh = ttk.Button(device_frame, text="새로고침", command=refresh_device_list)
btn_refresh.pack(side=tk.LEFT, padx=5)

pkg_label = tk.Label(root, text="com.example.qahelper", font=("Arial", 12), fg="blue")
pkg_label.pack(pady=5)

timer_label = tk.Label(root, text="녹화 시간: 00:00:00", font=("Arial", 14, "bold"), fg="red")
timer_label.pack(pady=5)

# 버튼 (ttk 버튼으로 일부 변경하여 일관성 유지)
btn_check = ttk.Button(root, text="현재 앱 확인", command=check_app)
btn_check.pack(pady=5, ipadx=40)
btn_reset = ttk.Button(root, text="지정 앱 초기화", command=init_app)
btn_reset.pack(pady=5, ipadx=40)
btn_relaunch = ttk.Button(root, text="지정 앱 재실행", command=relaunch)
btn_relaunch.pack(pady=5, ipadx=40)
btn_start_rec = ttk.Button(root, text="녹화 시작", command=start_recording_wrapper)
btn_start_rec.pack(pady=5, ipadx=40)
btn_stop_rec = ttk.Button(root, text="녹화 종료 + PC 저장", command=stop_recording_wrapper)
btn_stop_rec.pack(pady=5, ipadx=40)
btn_clear_log = ttk.Button(root, text="로그 초기화", command=clear_log)
btn_clear_log.pack(pady=5, ipadx=40)
btn_exit = ttk.Button(root, text="종료", command=root.destroy)
btn_exit.pack(pady=5, ipadx=40)

# 로그 출력
log_text = scrolledtext.ScrolledText(root, width=80, height=18)
log_text.pack(pady=10)

# 시작 시 패키지명 고정 및 기기 목록 자동 조회
pkg_label.config(text="com.example.qahelper")
refresh_device_list()

root.mainloop()