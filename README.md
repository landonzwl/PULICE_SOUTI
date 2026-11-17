# 拍照答题系统（手机直连大模型版）

手机拍下屏幕上的题目，直接调用大模型识别答案，把文字答案传到自己的服务器上，在另一台设备上查看。

> 图片不会上传到你的服务器，只在手机本地处理后删除，服务器只存答案文字。

---

## 📌 项目定位

**本项目是一个用于学习和练习网络通信的练手项目，主要目的是测试网络连通性。**

它用来验证一条完整链路是否通畅：

```
手机端相机采集 → HTTP 请求第三方大模型接口 → HTTP POST 回传服务器 → 浏览器实时展示
```

- 核心考察点：手机到第三方 API 的连通性、手机到自建服务器的连通性、服务器到浏览器的数据下发。
- 所有代码都是围绕"请求能不能通、数据能不能回传、页面能不能刷新"来写的。
- 仅供学习网络编程、HTTP 通信、Android 前台服务、FastAPI 接口等技术的同学参考。

本项目不具备也不应被赋予任何考试辅助用途，完整的行为约束见下方声明。

---

## ⚠️ 合法使用声明（请务必阅读）

**本项目严禁用于任何形式的考试作弊。**

- 不得在闭卷考试、随堂测验、资格认证考试等受考场纪律约束的场合使用本项目。
- 不得利用本项目协助他人作弊或为作弊提供便利。
- 使用本项目产生的一切后果由使用者自行承担，与项目作者无关。
- 如因违规使用导致学籍处分、考试成绩作废、法律责任等，作者不承担任何责任。

### ✅ 合法使用场景

本项目旨在提升**自学和练习**的效率，以下场景完全正当：

1. **自学刷题**：自己做练习题时，拍照快速核对答案，节省翻书时间。
2. **备考复习**：刷历年真题时，对照自己的答案和解析，查漏补缺。
3. **作业自查**：完成作业后，用 AI 检查自己的思路是否正确（但请独立完成作业）。
4. **知识问答**：遇到不会的题，先自己思考，再用本工具辅助理解解题思路。
5. **教学演示**：教师在课堂上演示 AI 辅助解题的流程，引导学生正确使用工具。

> 请记住：工具是辅助，学习靠自己。AI 给出的答案可能出错，务必独立思考、仔细核对。

---

## 快速开始（三步走）

### 第一步：准备大模型 API Key

1. 注册并登录 [SiliconFlow](https://cloud.siliconflow.cn/)
2. 充值几块钱（够答很多题）
3. 进入「API 密钥」页面，创建一个新密钥，复制下来（形如 `sk-xxxxxxxx`）

---

### 第二步：部署服务器（任意一台云服务器即可，2C2G 足够）

SSH 登录你的服务器，依次执行：

```bash
# 1. 上传本项目到服务器，假设解压到 /opt/souti
sudo mkdir -p /opt/souti
# （把项目文件上传到 /opt/souti，用 scp 或宝塔面板都行）

# 2. 安装 Python 依赖
cd /opt/souti
python3 -m venv .venv
.venv/bin/pip install fastapi uvicorn

# 3. 注册为系统服务（开机自启、崩溃自动重启）
sudo cp /opt/souti/deploy/souti.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now souti.service
```

检查是否成功：

```bash
sudo systemctl status souti          # 看到 active (running) 就对了
curl http://127.0.0.1/api/health     # 返回 {"ok":true}
```

> 云服务器的安全组/防火墙需要放行 **80 端口**。

访问地址（把 `你的服务器IP` 换成实际地址）：

```
http://你的服务器IP/          答案展示页
http://你的服务器IP/status   流水线状态与日志
```

---

### 第三步：打包手机 APP

1. 用 Android Studio 打开 `android/` 文件夹
2. 修改两个文件里的配置（**必须改**）：

   **文件 1**：`android/app/src/main/java/com/study/souti/solver/DirectModelClient.kt`

   找到这一行，把引号里的内容换成你第一步拿到的 API Key：
   ```kotlin
   const val API_KEY = "在这里填入你的SiliconFlow_API_Key"
   ```

   **文件 2**：`android/app/src/main/java/com/study/souti/config/CapturePreferences.kt`

   找到这两行，换成你自己的服务器地址和密码：
   ```kotlin
   const val DEFAULT_SERVER_URL = "http://你的服务器IP"
   const val DEFAULT_DEVICE_TOKEN = "自己设一个密码"
   ```

3. 打包 APK：在 Android Studio 里点 `Build > Build Bundle(s) / APK(s) > Build APK(s)`，或者命令行：
   ```bash
   cd android
   ./gradlew :app:assembleDebug      # macOS / Linux
   gradlew.bat :app:assembleDebug    # Windows
   ```
   生成的 APK 在 `android/app/build/outputs/apk/debug/app-debug.apk`。

4. 把 APK 装到手机上，打开 APP，点「开始」即可。

---

## 怎么用

1. 手机打开 APP，点「开始」
2. 把手机摄像头对准显示题目的屏幕
3. 答案会自动出现在 `http://你的服务器IP/` 页面上

APP 里也可以直接修改服务器地址、设备 ID、令牌，不需要重新打包。

---

## 常见问题

**Q：展示页没有答案？**
- 确认服务器在运行：`sudo systemctl status souti`
- 确认手机能访问服务器：手机浏览器打开 `http://你的服务器IP/api/health`，能看到 `{"ok":true}` 才行
- 看 `http://你的服务器IP/status` 流水线看板，卡在哪一步一目了然

**Q：模型返回错误 / 余额不足？**
- 登录 SiliconFlow 查看账户余额
- APP 界面会显示具体错误原因（HTTP 402 = 余额不足）

**Q：答案经常识别不出来？**
- 调整手机和屏幕的距离，保证题目清晰
- 可以在 APP 里把采集间隔调大一点（比如 5 秒）

---

## 项目结构

```
solver_server/   云端答案服务（FastAPI + SQLite），只存答案文字
android/         Android 采集端（拍照 → 大模型 → 上传答案）
deploy/          服务器部署文件（systemd 服务配置）
```

---

## 安全提醒

- 大模型 API Key 写在 APP 里，**不要把 APK 公开分发**，否则别人会用你的额度。
- 服务器只接收答案文字，不接收图片，不需要大带宽和大硬盘。
