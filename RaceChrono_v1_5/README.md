# RaceChrono v1.5.0 — Survival Edition
- Start/Finish as LINES (1-wide, multi-long/high) with chequered particles
- Splits (optional). Sidebar shows sectors only if present
- Live delta to personal-best while running
- Track descriptions + `/rc tracks info` for quick summaries
- Signs: Best (with holder), Top-3, Latest
- Ownership/permissions (admin/editor/sign/use)
- Default timeout 240s; configurable precision 1..3
Build:
  mvn -U -Dmaven.repo.local="$HOME/.m2fresh" clean package -DskipTests
