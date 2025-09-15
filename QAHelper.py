import subprocess
import re
import pyperclip
import tkinter as tk
from tkinter import messagebox, scrolledtext

# ----------------------------
# ADB 관련 함수
# ----------------------------
def run_adb(command):
    """ADB 명령어를 실행하고 결과를 반환하는 헬퍼 함수"""
    try:
        # Popen을 사용하여 명령어 실행 (셸 명령어 직접 실행)
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

def get_foreground_package():
    """현재 화면의 포그라운드 앱 패키지 이름을 가져오는 함수"""
    output = run_adb("adb shell dumpsys window")
    for line in output.splitlines():
        if "mCurrentFocus" in line or "mFocusedApp" in line:
            match = re.search(r" ([a-zA-Z0-9_.]+)/", line)
            if match:
                return match.group(1)
    return None

# ----------------------------
# com.example.qahelper 앱 제어 함수 (수정/추가된 부분)
# ----------------------------
def start_qahelper_recording(package_name):
    """Broadcast를 보내 QAHelper 앱의 녹화를 시작하는 함수"""
    log_text.insert(tk.END, f"[INFO] {package_name} 앱에 녹화 시작 명령 전송...\n")
    log_text.see(tk.END)
    
    action = f"{package_name}.action.START"
    receiver = f"{package_name}/.CommandBroadcastReceiver"
    command = f"adb shell am broadcast -a {action} -n {receiver}"
    
    result = run_adb(command)
    log_text.insert(tk.END, f"[RESULT] {result}\n")
    log_text.see(tk.END)

def stop_qahelper_recording(package_name):
    """Broadcast를 보내 QAHelper 앱의 녹화를 중지하는 함수"""
    log_text.insert(tk.END, f"[INFO] {package_name} 앱에 녹화 종료 명령 전송...\n")
    log_text.see(tk.END)

    action = f"{package_name}.action.STOP"
    receiver = f"{package_name}/.CommandBroadcastReceiver"
    command = f"adb shell am broadcast -a {action} -n {receiver}"
    
    result = run_adb(command)
    log_text.insert(tk.END, f"[RESULT] {result}\n")
    log_text.see(tk.END)
    
    # 녹화 종료 후 파일 다운로드 실행
    log_text.insert(tk.END, "[WAIT] 녹화 파일 저장을 위해 3초 대기...\n")
    log_text.see(tk.END)
    root.after(3000, lambda: pull_latest_video(package_name))

def pull_latest_video(package_name):
    """가장 최근에 녹화된 파일을 PC로 다운로드하는 함수"""
    log_text.insert(tk.END, "[INFO] 가장 최근 녹화된 파일 PC로 다운로드 시작...\n")
    log_text.see(tk.END)

    # 앱의 녹화 파일 저장 경로
    device_dir = f"/sdcard/Android/data/{package_name}/files/Movies"
    
    # 최신 파일 1개를 찾는 명령어
    find_command = f'adb shell "ls -t {device_dir}/*.mp4 | head -n 1"'
    latest_file_path = run_adb(find_command)

    if not latest_file_path or "[ERROR]" in latest_file_path:
        log_text.insert(tk.END, f"[ERROR] 녹화된 파일을 찾을 수 없습니다. 경로: {device_dir}\n")
        log_text.see(tk.END)
        messagebox.showerror("오류", f"녹화된 파일을 찾을 수 없습니다.\n경로: {device_dir}\nADB 연결 및 파일 저장 위치를 확인하세요.")
        return

    filename = latest_file_path.split('/')[-1]
    
    # PC로 파일 다운로드
    pull_command = f"adb pull {latest_file_path} ./{filename}"
    result = run_adb(pull_command)

    log_text.insert(tk.END, f"[INFO] 다운로드 완료: {filename}\n")
    log_text.insert(tk.END, f"[RESULT] {result}\n")
    log_text.see(tk.END)
    messagebox.showinfo("성공", f"녹화 파일 다운로드 완료!\n{filename}")

# ----------------------------
# 앱 초기화/재실행 (기존과 동일)
# ----------------------------
def reset_app(package_name, wait_time):
    log_text.insert(tk.END, f"[INFO] {package_name} 앱 초기화 시작...\n")
    log_text.see(tk.END)
    def step1():
        log_text.insert(tk.END, "[STEP1] 앱 강제 종료\n"); log_text.see(tk.END)
        run_adb(f"adb shell am force-stop {package_name}")
        root.after(500, step2)
    def step2():
        log_text.insert(tk.END, "[STEP2] 앱 데이터 삭제\n"); log_text.see(tk.END)
        run_adb(f"adb shell pm clear {package_name}")
        root.after(500, step3)
    def step3():
        log_text.insert(tk.END, f"[WAIT] 데이터 삭제 완료 대기 ({wait_time}초)\n"); log_text.see(tk.END)
        root.after(wait_time * 1000, step4)
    def step4():
        log_text.insert(tk.END, "[STEP3] 앱 재실행\n"); log_text.see(tk.END)
        output = run_adb(f"adb shell cmd package resolve-activity --brief {package_name}")
        activity_line = output.splitlines()[-1] if output else None
        if not activity_line:
            log_text.insert(tk.END, f"[ERROR] {package_name} Activity 확인 실패\n"); log_text.see(tk.END)
            return
        run_adb(f"adb shell am start -n {activity_line}")
        log_text.insert(tk.END, f"[INFO] {package_name} 앱 초기화 완료!\n"); log_text.see(tk.END)
    step1()

def relaunch_app(package_name):
    log_text.insert(tk.END, f"[INFO] {package_name} 앱 재실행 시작...\n"); log_text.see(tk.END)
    def step1():
        log_text.insert(tk.END, "[STEP1] 앱 강제 종료\n"); log_text.see(tk.END)
        run_adb(f"adb shell am force-stop {package_name}")
        root.after(500, step2)
    def step2():
        log_text.insert(tk.END, "[STEP2] 앱 실행\n"); log_text.see(tk.END)
        output = run_adb(f"adb shell cmd package resolve-activity --brief {package_name}")
        activity_line = output.splitlines()[-1] if output else None
        if not activity_line:
            log_text.insert(tk.END, f"[ERROR] {package_name} Activity 확인 실패\n"); log_text.see(tk.END)
            return
        run_adb(f"adb shell am start -n {activity_line}")
        log_text.insert(tk.END, f"[INFO] {package_name} 앱 재실행 완료!\n"); log_text.see(tk.END)
    step1()

# ----------------------------
# Tkinter GUI 함수
# ----------------------------
def check_app():
    pkg = get_foreground_package()
    if pkg:
        pkg_label.config(text=pkg)
        pyperclip.copy(pkg)
        log_text.insert(tk.END, f"[INFO] 현재 앱 패키지: {pkg} (클립보드 복사됨)\n")
        log_text.see(tk.END)
    else:
        messagebox.showerror("오류", "실행 중인 앱 패키지를 찾을 수 없습니다.")

def init_app():
    pkg = pkg_label.cget("text")
    if pkg and pkg != "패키지명 없음":
        try:
            wait_time = int(wait_time_entry.get())
        except ValueError:
            messagebox.showwarning("경고", "대기시간은 숫자로 입력하세요. 기본값 5초 적용.")
            wait_time = 5
        reset_app(pkg, wait_time)
    else:
        messagebox.showwarning("경고", "먼저 앱 패키지를 확인하세요.")

def relaunch():
    pkg = pkg_label.cget("text")
    if pkg and pkg != "패키지명 없음":
        relaunch_app(pkg)
    else:
        messagebox.showwarning("경고", "먼저 앱 패키지를 확인하세요.")

def start_recording_wrapper():
    # pkg = pkg_label.cget("text") # 현재 활성화된 앱 기준
    pkg = "com.example.qahelper" # 패키지명 고정
    if pkg and pkg != "패키지명 없음":
        start_qahelper_recording(pkg)
    else:
        messagebox.showwarning("경고", "먼저 앱 패키지를 확인하세요.")

def stop_recording_wrapper():
    # pkg = pkg_label.cget("text") # 현재 활성화된 앱 기준
    pkg = "com.example.qahelper" # 패키지명 고정
    if pkg and pkg != "패키지명 없음":
        stop_qahelper_recording(pkg)
    else:
        messagebox.showwarning("경고", "먼저 앱 패키지를 확인하세요.")

def clear_log():
    log_text.delete("1.0", tk.END)

# ----------------------------
# GUI 구성
# ----------------------------
root = tk.Tk()
root.title("QA Helper GUI for com.example.qahelper")
root.geometry("600x580")

pkg_label = tk.Label(root, text="com.example.qahelper", font=("Arial", 12), fg="blue")
pkg_label.pack(pady=10)

wait_frame = tk.Frame(root)
wait_frame.pack(pady=5)
tk.Label(wait_frame, text="데이터 삭제 대기시간(초):").pack(side=tk.LEFT)
wait_time_entry = tk.Entry(wait_frame, width=5)
wait_time_entry.insert(0, "5")
wait_time_entry.pack(side=tk.LEFT)

btn_check = tk.Button(root, text="현재 앱 확인", width=25, command=check_app)
btn_check.pack(pady=5)

btn_reset = tk.Button(root, text="QAHelper 앱 초기화", width=25, command=init_app)
btn_reset.pack(pady=5)

btn_relaunch = tk.Button(root, text="QAHelper 앱 재실행", width=25, command=relaunch)
btn_relaunch.pack(pady=5)

btn_start_rec = tk.Button(root, text="QAHelper 녹화 시작", width=25, command=start_recording_wrapper, bg="lightblue")
btn_start_rec.pack(pady=5)

btn_stop_rec = tk.Button(root, text="QAHelper 녹화 종료 + PC 저장", width=25, command=stop_recording_wrapper, bg="lightcoral")
btn_stop_rec.pack(pady=5)

btn_clear_log = tk.Button(root, text="로그 초기화", width=25, command=clear_log)
btn_clear_log.pack(pady=5)

btn_exit = tk.Button(root, text="종료", width=25, command=root.destroy)
btn_exit.pack(pady=5)

log_text = scrolledtext.ScrolledText(root, width=80, height=20)
log_text.pack(pady=10)

# 시작 시 패키지명 고정
pkg_label.config(text="com.example.qahelper")

root.mainloop()