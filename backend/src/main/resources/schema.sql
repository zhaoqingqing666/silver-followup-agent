CREATE TABLE IF NOT EXISTS users (
  id VARCHAR(40) PRIMARY KEY,
  name VARCHAR(40) NOT NULL,
  home_address VARCHAR(200),
  preferred_transport VARCHAR(40)
);

CREATE TABLE IF NOT EXISTS hospitals (
  id VARCHAR(40) PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  address VARCHAR(200) NOT NULL
);

ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS hospital_level VARCHAR(40);
ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS description VARCHAR(500);
ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS specialty_tags VARCHAR(300);
ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS elderly_services VARCHAR(300);
ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS enabled BOOLEAN DEFAULT TRUE NOT NULL;

CREATE TABLE IF NOT EXISTS departments (
  id VARCHAR(40) PRIMARY KEY,
  hospital_id VARCHAR(40) NOT NULL,
  name VARCHAR(60) NOT NULL
);

ALTER TABLE departments ADD COLUMN IF NOT EXISTS description VARCHAR(500);
ALTER TABLE departments ADD COLUMN IF NOT EXISTS specialty_tags VARCHAR(300);
ALTER TABLE departments ADD COLUMN IF NOT EXISTS followup_scope VARCHAR(300);
ALTER TABLE departments ADD COLUMN IF NOT EXISTS location VARCHAR(100);
ALTER TABLE departments ADD COLUMN IF NOT EXISTS enabled BOOLEAN DEFAULT TRUE NOT NULL;

CREATE TABLE IF NOT EXISTS appointment_slots (
  id VARCHAR(40) PRIMARY KEY,
  hospital_id VARCHAR(40) NOT NULL,
  hospital_name VARCHAR(100) NOT NULL,
  department VARCHAR(60) NOT NULL,
  appointment_date DATE NOT NULL,
  appointment_time TIME NOT NULL,
  available BOOLEAN NOT NULL
);

CREATE TABLE IF NOT EXISTS user_schedules (
  id VARCHAR(40) PRIMARY KEY,
  user_id VARCHAR(40) NOT NULL,
  title VARCHAR(120) NOT NULL,
  start_at TIMESTAMP NOT NULL,
  end_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS family_contacts (
  id VARCHAR(40) PRIMARY KEY,
  user_id VARCHAR(40) NOT NULL,
  name VARCHAR(40) NOT NULL,
  relationship VARCHAR(40) NOT NULL,
  phone VARCHAR(30) NOT NULL
);

CREATE TABLE IF NOT EXISTS appointments (
  id VARCHAR(50) PRIMARY KEY,
  slot_id VARCHAR(40) NOT NULL,
  user_id VARCHAR(40) NOT NULL,
  status VARCHAR(30) NOT NULL,
  created_at TIMESTAMP NOT NULL
);

ALTER TABLE appointments ADD COLUMN IF NOT EXISTS departure_time TIMESTAMP;
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS transport VARCHAR(40);
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS reminder_status VARCHAR(100);
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS family_status VARCHAR(100);
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS materials CLOB;

CREATE TABLE IF NOT EXISTS material_templates (
  id VARCHAR(50) PRIMARY KEY,
  department VARCHAR(60) NOT NULL,
  material_name VARCHAR(120) NOT NULL,
  required BOOLEAN NOT NULL,
  sort_order INT NOT NULL
);

CREATE TABLE IF NOT EXISTS appointment_materials (
  id VARCHAR(50) PRIMARY KEY,
  appointment_id VARCHAR(50) NOT NULL,
  material_code VARCHAR(50) NOT NULL,
  material_name VARCHAR(120) NOT NULL,
  required BOOLEAN NOT NULL,
  status VARCHAR(30) NOT NULL,
  confirm_source VARCHAR(30),
  photo_url VARCHAR(500),
  updated_at TIMESTAMP NOT NULL,
  UNIQUE (appointment_id, material_code)
);

CREATE TABLE IF NOT EXISTS user_preferences (
  user_id VARCHAR(40) PRIMARY KEY,
  auto_speak_enabled BOOLEAN NOT NULL,
  speech_rate DOUBLE PRECISION NOT NULL,
  speech_volume DOUBLE PRECISION NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS travel_routes (
  id VARCHAR(50) PRIMARY KEY,
  origin VARCHAR(200) NOT NULL,
  destination VARCHAR(200) NOT NULL,
  transport VARCHAR(40) NOT NULL,
  duration_minutes INT NOT NULL
);

CREATE TABLE IF NOT EXISTS reminders (
  id VARCHAR(50) PRIMARY KEY,
  user_id VARCHAR(40) NOT NULL,
  title VARCHAR(120) NOT NULL,
  remind_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS family_notifications (
  id VARCHAR(50) PRIMARY KEY,
  contact_id VARCHAR(40) NOT NULL,
  content VARCHAR(500) NOT NULL,
  status VARCHAR(30) NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS conversation_sessions (
  id VARCHAR(50) PRIMARY KEY,
  user_id VARCHAR(40) NOT NULL,
  stage VARCHAR(60) NOT NULL,
  state_json CLOB NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

ALTER TABLE conversation_sessions ADD COLUMN IF NOT EXISTS last_response_json CLOB;

-- 会话生命周期：与办理进度（stage）相互独立，CLOSED 不进入 stage 状态机。
-- status: ACTIVE / CLOSED；close_reason: NEW_CONVERSATION / IDLE_TIMEOUT / TURN_LIMIT / TOKEN_LIMIT
ALTER TABLE conversation_sessions ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'ACTIVE' NOT NULL;
ALTER TABLE conversation_sessions ADD COLUMN IF NOT EXISTS last_message_at TIMESTAMP;
ALTER TABLE conversation_sessions ADD COLUMN IF NOT EXISTS closed_at TIMESTAMP;
ALTER TABLE conversation_sessions ADD COLUMN IF NOT EXISTS close_reason VARCHAR(40);
ALTER TABLE conversation_sessions ADD COLUMN IF NOT EXISTS turn_count INT DEFAULT 0 NOT NULL;
ALTER TABLE conversation_sessions ADD COLUMN IF NOT EXISTS estimated_tokens INT DEFAULT 0 NOT NULL;

CREATE TABLE IF NOT EXISTS conversation_messages (
  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  conversation_id VARCHAR(50) NOT NULL,
  role VARCHAR(20) NOT NULL,
  content CLOB NOT NULL,
  created_at TIMESTAMP NOT NULL
);

ALTER TABLE conversation_messages ADD COLUMN IF NOT EXISTS message_type VARCHAR(20) DEFAULT 'TEXT' NOT NULL;
ALTER TABLE conversation_messages ADD COLUMN IF NOT EXISTS attachment_id BIGINT;

-- 会话内图片附件：演示阶段直接存 dataUrl，业务层只通过本表读取，不直接依赖 dataUrl 形态，
-- 后续换成文件存储时只需替换这一层的读写实现。
CREATE TABLE IF NOT EXISTS conversation_attachments (
  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  conversation_id VARCHAR(50) NOT NULL,
  message_id BIGINT,
  kind VARCHAR(20) NOT NULL,
  data_url CLOB NOT NULL,
  created_at TIMESTAMP NOT NULL
);

-- 图片的视觉识别结果：与附件分开存，后续轮次可直接复用，不必重新调用视觉模型。
-- description 是给"这图片是什么"用的简短摘要；ocr 是图片上的完整文字，供"全部念出来/规格/批准文号"等追问直接读取。
CREATE TABLE IF NOT EXISTS vision_results (
  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  attachment_id BIGINT NOT NULL,
  conversation_id VARCHAR(50) NOT NULL,
  hint VARCHAR(500),
  description CLOB NOT NULL,
  created_at TIMESTAMP NOT NULL
);

ALTER TABLE vision_results ADD COLUMN IF NOT EXISTS ocr CLOB;

CREATE TABLE IF NOT EXISTS tool_call_logs (
  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  conversation_id VARCHAR(50) NOT NULL,
  tool_name VARCHAR(80) NOT NULL,
  request_json CLOB NOT NULL,
  response_json CLOB NOT NULL,
  success BOOLEAN NOT NULL,
  created_at TIMESTAMP NOT NULL
);

ALTER TABLE tool_call_logs ADD COLUMN IF NOT EXISTS response_json CLOB;

CREATE TABLE IF NOT EXISTS user_memories (
  id VARCHAR(50) PRIMARY KEY,
  user_id VARCHAR(40) NOT NULL,
  hospital VARCHAR(100),
  department VARCHAR(60),
  appointment_date DATE,
  result VARCHAR(60) NOT NULL,
  appointment_id VARCHAR(50),
  conversation_id VARCHAR(50),
  created_at TIMESTAMP NOT NULL
);
