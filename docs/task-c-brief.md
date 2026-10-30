# 任务 C 任务书：CI/CD 补完

> **这份文档是给下一次对话用的**，假设读者没有任何上下文。目标是把路线图的 P2「CI/CD 完善」
> 做完。当前起点是 `master` 分支的 `dec2b16`（任务 K 已完成并推送）。
>
> 执行前先读：
> 1. [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) —— 现有的测试流水线；
> 2. [`.github/workflows/sdk.yml`](../.github/workflows/sdk.yml)、[`sdk-publish.yml`](../.github/workflows/sdk-publish.yml)、[`helm.yml`](../.github/workflows/helm.yml) —— 已有的三个独立 job；
> 3. [`pom.xml`](../pom.xml) —— 注意 `argLine` 属性和插件区；
> 4. [`Dockerfile`](../Dockerfile) 与 [`frontend/package.json`](../frontend/package.json) —— 镜像不构建前端；
> 5. [`helm/hookflow/Chart.yaml`](../helm/hookflow/Chart.yaml) —— Helm 发布的对象；
> 6. [`README.md`](../README.md) 顶部徽章与 [`docs/remaining-plan.md`](remaining-plan.md) §5。

---

## 0. 当前基线（先确认，别假设）

| 项 | 值 | 怎么确认 |
|---|---|---|
| 分支 / 最新 commit | `master` / `dec2b16`（K 的 Helm chart） | `git log --oneline -1` |
| 后端单测 | **164 个通过** | `.\mvnw.cmd -o test` |
| 后端集成测试 | **69 个通过**（Testcontainers，需 Docker） | `.\mvnw.cmd -o verify` |
| 前端单测 | **77 个通过**（15 文件） | `cd frontend; npm test` |
| Java SDK | **66 个通过**，JaCoCo LINE 92.9% | `.\mvnw.cmd -f sdk/java/pom.xml verify` |
| 已有 workflow | `ci.yml`、`sdk.yml`、`sdk-publish.yml`、`helm.yml` | `Get-ChildItem .github/workflows` |
| 根 pom JaCoCo | **没有** | `Select-String pom.xml -Pattern jacoco` 无结果 |
| README CI 徽章 | 静态 shields 假徽章 | 第 5 行左右：`img.shields.io/badge/CI-GitHub...` |
| 前端构建产物 | `npm run build:backend` 写入 `src/main/resources/static/ui/`（gitignore） | `frontend/package.json` |

**当前 CI 的实际行为**：

- `ci.yml`：push/PR 跑 `./mvnw -B test`、`./mvnw -B verify`、`./mvnw -B -DskipTests package`；
  frontend job 跑 `npm ci`、typecheck、lint、test:coverage、build；上传 surefire/frontend coverage artifact。
- `sdk.yml`：只在 `sdk/java/**` 或它自己变更时跑 `mvn verify` + javadoc。
- `sdk-publish.yml`：tag `sdk-v*` 时 `mvn deploy` 到 GitHub Packages（**尚未实际发布**）。
- `helm.yml`：只在 `helm/**`、`deploy/kind/**` 变更时跑 `helm lint` + `helm template`。
- **没有** Docker 镜像 workflow，**没有** Helm OCI 发布 workflow，**没有** Codecov，**没有**实时徽章。

> 注意：仓库的 README 在 PowerShell `Get-Content` 里可能显示乱码，这是控制台编码问题，
> 文件本身是 UTF-8。改 README 用编辑工具，不要用 `Set-Content`（会写 BOM/乱码）。

---

## 1. 目标与验收

路线图 [optimization-roadmap.md](optimization-roadmap.md) §P2 CI/CD（L1758 起）的五条：

- [ ] 每次 push 自动运行测试
- [ ] 覆盖率报告自动上传到 Codecov
- [ ] master 分支自动构建 Docker 镜像
- [ ] 打 tag 自动发布 Helm Chart
- [ ] README 显示 CI 状态徽章

本计划补充一条：

- [ ] SDK（Java）与 Python SDK 的 job 都在 CI 里（**Python SDK 尚未实现，见 §4.5**）

**完成时必须能逐条指到一个 workflow 步骤**；不能验证的（例如没有 `CODECOV_TOKEN`、
没有 GHCR 推送权限）要在文档里明确写「未验证/未启用」，**不要声称已绿**。

---

## 2. 设计决策（先定，避免走错）

| 决策 | 选择 | 理由 |
|---|---|---|
| 覆盖率采集方式 | **独立的 `coverage` Maven profile**，只跑单测 | `verify` 的 IT 不跑覆盖率，避免拖慢 CI；默认 `mvn test` 完全不变 |
| Codecov token | 没有 `CODECOV_TOKEN` 就**跳过上传**，并在 README/文档标注 | 本仓库是公开仓库也不要在没有 token 的情况下硬失败 |
| Docker 镜像 | `ghcr.io/shifang37/hookflow` | 与 SDK 的 GitHub Packages 同一账号，`GITHUB_TOKEN` 即可 |
| Docker 构建上下文 | **workflow 先构建前端，再 docker build** | Dockerfile 只 `COPY src/`，不跑 npm；不先 `npm run build:backend` 镜像里没有 `/ui/` |
| Helm 发布 | **OCI**：`oci://ghcr.io/shifang37/charts`，tag `helm-v*` 触发 | 一条 workflow 完成，不依赖 gh-pages/chart-releaser |
| Python SDK | **只能预置 workflow，不能声称已测试** | 任务 C 在 P 之前；`sdk/python/` 现在不存在。见 §4.5 |
| 徽章 | 用 GitHub Actions 的实时徽章 URL | 替换静态 shields；徽章在 workflow 至少跑一次后才显示 |

---

## 3. 工作项

### 3.1 C1：JaCoCo（单测）+ Codecov

#### 3.1.1 改 `pom.xml`

根 pom 的 `<properties>` 里现有：

```xml
<argLine>-Duser.timezone=UTC</argLine>
```

**这是最大的坑**：JaCoCo 的 `prepare-agent` 默认也写 `argLine` 属性，会把时区参数覆盖掉，
而测试依赖固定 UTC（很多时间断言会红）。

正确做法：

1. 在 `<properties>` 里加一个**默认为空**的 JaCoCo 参数占位：

```xml
<jacoco.argLine></jacoco.argLine>
```

2. 在 `<build><plugins>` 里显式配 surefire（版本交给 Spring Boot parent 管理）：

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <argLine>-Duser.timezone=UTC ${jacoco.argLine}</argLine>
  </configuration>
</plugin>
```

3. 加一个 **profile**（不要绑到默认生命周期，否则 `verify` 的 IT 也会被 agent 覆盖）：

```xml
<profile>
  <id>coverage</id>
  <build>
    <plugins>
      <plugin>
        <groupId>org.jacoco</groupId>
        <artifactId>jacoco-maven-plugin</artifactId>
        <version>0.8.12</version>
        <configuration>
          <propertyName>jacoco.argLine</propertyName>
        </configuration>
        <executions>
          <execution>
            <id>prepare-agent</id>
            <goals><goal>prepare-agent</goal></goals>
          </execution>
          <execution>
            <id>report</id>
            <phase>test</phase>
            <goals><goal>report</goal></goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</profile>
```

验证：

```powershell
.\mvnw.cmd -o -B -Pcoverage test
Test-Path target\site\jacoco\jacoco.xml
# 仍然必须是 164 个通过；如果时间断言红了，就是 argLine 没接对
```

若 `jacoco.xml` 没生成（`report` 在 test 阶段没抢到 after surefire），显式再跑：

```powershell
.\mvnw.cmd -o -B -Pcoverage jacoco:report
```

> 可选：`<excludes>` 排除生成的 `**/dto/**` 等，但先不加，保持真实数字。

#### 3.1.2 改 `.github/workflows/ci.yml`

在现有 `Unit tests` 之后加一个**带覆盖率的单测步骤**（不要替换 `verify`）：

```yaml
      - name: Unit tests with coverage
        run: ./mvnw -B -Pcoverage test

      - name: Upload coverage to Codecov
        if: ${{ github.event_name != 'pull_request' && secrets.CODECOV_TOKEN != '' }}
        uses: codecov/codecov-action@v4
        with:
          files: target/site/jacoco/jacoco.xml
          token: ${{ secrets.CODECOV_TOKEN }}
          fail_ci_if_error: false

      - name: Note missing Codecov token
        if: ${{ secrets.CODECOV_TOKEN == '' }}
        run: echo "CODECOV_TOKEN is not configured; coverage was generated but not uploaded."
```

注意：

- `secrets` 在 fork PR 里不可用，所以上传只在非 PR 且 token 存在时跑。
- `codecov-action@v4` 需要 token；没有 token 时 `if` 直接跳过，不要让它失败。
- 可选加 `codecov.yml`（project/patch 阈值），但**不要**在没有历史数据时设硬 gate。

#### 3.1.3 验收

- `mvn -Pcoverage test` 绿且 164 个测试；
- `target/site/jacoco/jacoco.xml` 存在；
- CI 日志里 Codecov 步骤要么成功上传，要么明确打印「token 未配置」；
- 文档记录 LINE 覆盖率数字（从 `target/site/jacoco/jacoco.csv` 或 HTML 读）。

---

### 3.2 C2：`.github/workflows/docker.yml`（master + tag 推 GHCR）

新建 `.github/workflows/docker.yml`，要求：

- 只在 **push master** 和 **push tag `v*`** 触发（不要让 `sdk-v*`、`helm-v*` 触发；`v*` 不匹配它们，天然安全）；
- `permissions: contents: read, packages: write`；
- **先构建前端**再 docker build；
- 用 buildx + `docker/login-action` 登录 GHCR；
- tag：sha、`latest`（仅 master）、tag 名；
- 缓存：`type=gha`。

骨架：

```yaml
name: docker

on:
  push:
    branches: [master]
    tags: ['v*']

permissions:
  contents: read
  packages: write

jobs:
  image:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up Node 22
        uses: actions/setup-node@v4
        with:
          node-version: '22'
          cache: npm
          cache-dependency-path: frontend/package-lock.json

      # 关键：Dockerfile 不会跑 npm，镜像里的 /ui/ 取决于这一步。
      - name: Build console into the jar resources
        working-directory: frontend
        run: |
          npm ci
          npm run build:backend

      - uses: docker/setup-buildx-action@v3

      - name: Log in to GHCR
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Metadata
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ghcr.io/${{ github.repository }}
          tags: |
            type=sha
            type=raw,value=latest,enable={{is_default_branch}}
            type=ref,event=tag

      - name: Build and push
        uses: docker/build-push-action@v6
        with:
          context: .
          push: true
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

验证与注意：

- 本地无法完整验证 GHCR 推送（需要 token）；至少要验证 workflow YAML 语法和
  `docker build` 在**前端构建后**能成功（本机 Docker 可能因 Maven 网络失败，见 §5）。
- `frontend/scripts/copy-dist-to-backend.mjs` 会把 `dist` 复制到 `src/main/resources/static/ui/`；
  Docker 的 `COPY src/` 会把它带进去。
- 不要提交 `src/main/resources/static/ui/`（已在 `.gitignore`）。
- `docker/metadata-action` 的 `{{is_default_branch}}` 只对 master 生效，tag 不会得到 `latest`。

---

### 3.3 C3：`.github/workflows/helm-publish.yml`（tag 推 Helm OCI）

新建一个**独立于 `helm.yml`** 的发布 workflow：

```yaml
name: helm-publish

on:
  push:
    tags: ['helm-v*']

permissions:
  contents: read
  packages: write

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up Helm
        uses: azure/setup-helm@v4
        with:
          version: v3.16.4

      - name: Log in to GHCR
        run: echo "${{ secrets.GITHUB_TOKEN }}" | helm registry login ghcr.io -u ${{ github.actor }} --password-stdin

      - name: Package and push
        run: |
          version="${GITHUB_REF_NAME#helm-v}"
          mkdir -p dist
          helm package helm/hookflow --version "$version" --destination dist
          helm push "dist/hookflow-${version}.tgz" oci://ghcr.io/shifang37/charts
```

说明：

- Chart 的 `version` 在 `Chart.yaml` 里是 `0.1.0`；用 `--version` 从 tag 覆盖，避免 tag 与 chart 版本不一致。
- `helm push` 的 OCI 路径最后一段是仓库名（`charts`），包名是 `hookflow`。
- 本地验证只能做 `helm package helm/hookflow -d dist`；
  **实际 push 需要 GHCR 权限，本次不声称已验证发布**。
- 不要和 `sdk-publish.yml`（`sdk-v*`）抢 tag。

---

### 3.4 C4：README 实时徽章

替换 README 第 5 行附近的静态：

```markdown
[![CI](https://img.shields.io/badge/CI-GitHub%20Actions-2ea44f)](.github/workflows/ci.yml)
```

改成（注意 `branch=master`）：

```markdown
[![CI](https://github.com/shifang37/hookflow/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/shifang37/hookflow/actions/workflows/ci.yml)
[![Docker](https://github.com/shifang37/hookflow/actions/workflows/docker.yml/badge.svg?branch=master)](https://github.com/shifang37/hookflow/actions/workflows/docker.yml)
[![Helm](https://github.com/shifang37/hookflow/actions/workflows/helm.yml/badge.svg?branch=master)](https://github.com/shifang37/hookflow/actions/workflows/helm.yml)
[![Java SDK](https://github.com/shifang37/hookflow/actions/workflows/sdk.yml/badge.svg?branch=master)](https://github.com/shifang37/hookflow/actions/workflows/sdk.yml)
```

可选 Codecov 徽章（只有 token 上传成功后再加，否则会长期 unknown）：

```markdown
[![codecov](https://codecov.io/gh/shifang37/hookflow/branch/master/graph/badge.svg)](https://codecov.io/gh/shifang37/hookflow)
```

注意：徽章只有在对应 workflow 至少跑过一次后才会变成绿色；刚推送时显示 "no status"。

---

### 3.5 C5：SDK job（Java 已有；Python 只能预置）

- **Java**：`sdk.yml` 已存在，覆盖 `sdk/java/**`；C 只需要在 README 加徽章，**不要重复建 job**。
- **Python**：`sdk/python/` 现在不存在（任务 P 还没做）。本次新建一个
  `.github/workflows/python-sdk.yml`，只在 `sdk/python/**` 变更时触发：

```yaml
name: python-sdk

on:
  push:
    paths: ['sdk/python/**', '.github/workflows/python-sdk.yml']
  pull_request:
    paths: ['sdk/python/**', '.github/workflows/python-sdk.yml']

jobs:
  test:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: sdk/python
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: '3.12'
      - run: pip install -e '.[dev]'
      - run: mypy .
      - run: pytest --cov=hookflow --cov-report=xml
```

说明：

- 由于路径过滤，**现在它不会运行**；任务 P 落地后自动生效。这是有意为之，不是遗漏。
- 因此 C 的验收第 6 条只能标「Python job 已预置，待 P 完成后生效」；
  **不要**声称 Python SDK 已经过 CI。
- 若用户希望 C 一次性完成该条，则应先做 P，再回来把 workflow 点亮；按当前计划顺序
  （C → U → P）不这么做。

---

## 4. 本地验证

```powershell
# 1. 回归
.\mvnw.cmd -o -B test                    # 164
.\mvnw.cmd -o -B -Pcoverage test         # 164 + jacoco.xml
Test-Path target\site\jacoco\jacoco.xml
cd frontend; npm test; cd ..             # 77（C 不改前端，应保持）

# 2. Helm 包
$helm = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter helm.exe | Select-Object -First 1).FullName
& $helm package helm/hookflow --destination .tmp-helm-package
& $helm lint helm/hookflow -f helm/hookflow/values-dev.yaml

# 3. Docker 镜像（如果网络允许；先构建前端）
cd frontend; $env:TEMP=(Resolve-Path .tmp).Path; npm run build:backend; cd ..
docker build -t hookflow:ci-check .
```

Workflow YAML 的本地校验：

- 如果机器上有 `actionlint`，跑 `actionlint .github/workflows/*.yml`（没有就跳过，不要硬装）。
- 没有 `actionlint` 时至少用编辑工具检查缩进/引号，并跑一次 `git diff --check`。

> 本机网络对 GitHub/GHCR 不稳定：`git push` 可能 TLS EOF，重试即可；GHCR 的 workflow
> 结果只能在 GitHub Actions 页面看，本地无法伪造。

---

## 5. 已知坑（血泪版）

1. **`argLine` 时区覆盖**：根 pom 的 `-Duser.timezone=UTC` 必须保留；JaCoCo 用
   `propertyName=jacoco.argLine` + surefire 显式拼接，否则一批时间断言会红。
2. **不要把 JaCoCo 绑到默认 `verify`**：IT 会被 agent 覆盖，Codecov 数字也会混入
   Testcontainers 路径；只用 `-Pcoverage test`。
3. **Dockerfile 不构建前端**：workflow 必须先 `npm ci && npm run build:backend`，
   否则镜像能跑 API 但 `/ui/` 404。不要为此改 Dockerfile 也可以（二选一，但要在
   README 说明选了哪条）。
4. **Codecov 没有 token**：`if` 跳过，并在日志里说明；不要在文档里写「已上传」。
5. **GHCR 权限**：workflow 必须 `packages: write`；仓库 Settings → Actions → General 的
   workflow permissions 不能是 read-only。
6. **tag 命名冲突**：`docker.yml` 用 `v*`；`sdk-publish.yml` 用 `sdk-v*`；Helm 用 `helm-v*`。
   不要让 `v*` 匹配到 `sdk-v*`（它不匹配，但别改成 `*`）。
7. **Helm chart 版本**：`Chart.yaml` 是 `0.1.0`，发布时用 tag 覆盖 `--version`；
   `helm push` 的 OCI 地址最后一段是仓库名，不是包名。
8. **README 徽章分支**：仓库默认分支是 `master`；写 `branch=master`，不要用 `main`。
9. **README 编码**：用编辑工具改；PowerShell `Set-Content` 会写 BOM/乱码（这条已经踩过两次）。
10. **Python SDK 还不存在**：`python-sdk.yml` 只是预置；不要在 C 的验收里勾成「Python SDK 已通过 CI」。
11. **`kind load` / Docker 29**（任务 K 的经验，C 的 Docker workflow 不涉及，但本地跑镜像时可能遇到）：
    `kind load docker-image` 可能报 digest not found，改用 `docker save` + `ctr import`。
12. **不要删掉 `ci.yml` 现有的 `verify` 步骤**：C 只增加覆盖率和其它 workflow；IT 仍是
    质量底线。

---

## 6. 文档更新

完成后至少改：

- [`docs/next-steps.md`](next-steps.md)：P2 CI/CD 行改成实际状态（哪些已验证/哪些没有 token）；
  更新「建议的下一步」为 U（控制台小缺口）。
- [`docs/remaining-plan.md`](remaining-plan.md)：§5 任务 C 的 checklist、§11 的 P2 行打勾/注明部分完成。
- [`docs/optimization-roadmap.md`](optimization-roadmap.md)：P2 CI/CD 验收五条按实际打勾，
  并加一段「实现位置」指向 workflow。
- [`README.md`](../README.md)：徽章 + 一节 CI/CD workflow 表（ci / docker / helm / sdk / sdk-publish）。
- 新 workflow 的注释写清触发条件和为什么（tag 命名、权限、前端构建）。

**不要**改 `CHANGELOG.md`（当前文件编码有问题，历史遗留），除非用户明确要求。

---

## 7. 完成定义（Checklist）

- [ ] `mvn -Pcoverage test` 绿、生成 `target/site/jacoco/jacoco.xml`、164 个测试
- [ ] `ci.yml` 有 Codecov 步骤；无 token 时跳过并打印原因
- [ ] `docker.yml` 存在，master/tag 触发，先构建前端，buildx 推 GHCR，sha/latest/tag 标签
- [ ] `helm-publish.yml` 存在，`helm-v*` 触发，OCI 推 `ghcr.io/shifang37/charts`
- [ ] README 静态 CI 徽章换成实时徽章（CI/Docker/Helm/Java SDK；Codecov 可选）
- [ ] `python-sdk.yml` 预置，路径触发，明确「P 落地前不运行」
- [ ] 本地 `test`/`verify`/前端测试没有回归
- [ ] 文档四处的状态/数字与实际一致；没验证的（GHCR 推送、Codecov 上传、Helm push）明确标注
- [ ] 一个英文 commit（例如 `ci: add coverage, image and chart publishing`），文档同 commit
- [ ] 尝试 `git push origin master`；如果网络失败，报告失败而不是声称已推送

---

## 8. 明确不做（避免 scope creep）

- 不把根 pom 改成 aggregator，不把 `sdk/java` 并进根构建。
- 不做 ArgoCD/GitOps、不做镜像签名/Trivy、不做多环境部署流水线。
- 不为了 C 去实现 Python SDK；P 是独立任务。
- 不删除任何现有 workflow 的质量步骤。
- 不为了徽章变绿而伪造 CI 运行结果。
