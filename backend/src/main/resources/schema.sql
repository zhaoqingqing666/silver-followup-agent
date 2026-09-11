CREATE TABLE IF NOT EXISTS users (
  id VARCHAR(40) PRIMARY KEY,
  name VARCHAR(40) NOT NULL,
  home_address VARCHAR(200),
  preferred_transport VARCHAR(40)
);

ALTER TABLE users ADD COLUMN IF NOT EXISTS home_longitude DOUBLE PRECISION;
ALTER TABLE users ADD COLUMN IF NOT EXISTS home_latitude DOUBLE PRECISION;

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
ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION;
ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION;
ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS main_entrance VARCHAR(100);

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

CREATE TABLE IF NOT EXISTS clinic_locations (
  id VARCHAR(40) PRIMARY KEY,
  hospital_id VARCHAR(40) NOT NULL,
  department_id VARCHAR(40) NOT NULL,
  building_name VARCHAR(100) NOT NULL,
  entrance_name VARCHAR(100) NOT NULL,
  floor_name VARCHAR(40) NOT NULL,
  room_name VARCHAR(60) NOT NULL,
  check_in_point VARCHAR(120) NOT NULL,
  landmark VARCHAR(200),
  accessible_route_hint VARCHAR(500),
  help_desk VARCHAR(120),
  verified_at TIMESTAMP NOT NULL,
  enabled BOOLEAN DEFAULT TRUE NOT NULL
);

CREATE TABLE IF NOT EXISTS appointment_slots (
  id VARCHAR(40) PRIMARY KEY,
  hospital_id VARCHAR(40) NOT NULL,
  hospital_name VARCHAR(100) NOT NULL,
  department VARCHAR(60) NOT NULL,
  appointment_date DATE NOT NULL,
  appointment_time TIME NOT NULL,
  available BOOLEAN NOT NULL
);

ALTER TABLE appointment_slots ADD COLUMN IF NOT EXISTS clinic_location_id VARCHAR(40);

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

CREATE TABLE IF NOT EXISTS care_guide_articles (
  id VARCHAR(50) PRIMARY KEY,
  topic VARCHAR(50) NOT NULL,
  title VARCHAR(120) NOT NULL,
  content CLOB NOT NULL,
  keywords VARCHAR(300) NOT NULL,
  hospital_name VARCHAR(100),
  department VARCHAR(60),
  sort_order INT NOT NULL,
  enabled BOOLEAN DEFAULT TRUE NOT NULL
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

ALTER TABLE travel_routes ADD COLUMN IF NOT EXISTS distance_meters INT;
ALTER TABLE travel_routes ADD COLUMN IF NOT EXISTS route_steps CLOB;
ALTER TABLE travel_routes ADD COLUMN IF NOT EXISTS polyline CLOB;
ALTER TABLE travel_routes ADD COLUMN IF NOT EXISTS route_source VARCHAR(30) DEFAULT 'SIMULATED';

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

CREATE TABLE IF NOT EXISTS conversation_messages (
  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  conversation_id VARCHAR(50) NOT NULL,
  role VARCHAR(20) NOT NULL,
  content CLOB NOT NULL,
  created_at TIMESTAMP NOT NULL
);

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

ALTER TABLE appointments ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(50);
ALTER TABLE reminders ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(50);
ALTER TABLE reminders ADD COLUMN IF NOT EXISTS appointment_id VARCHAR(50);
ALTER TABLE reminders ADD COLUMN IF NOT EXISTS status VARCHAR(30) DEFAULT 'CREATED';
ALTER TABLE family_notifications ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(50);

-- 协同照护端：家属/志愿者与其协同的就诊人关系（一对多）
CREATE TABLE IF NOT EXISTS care_relations (
  id VARCHAR(40) PRIMARY KEY,
  caregiver_id VARCHAR(40) NOT NULL,
  elder_user_id VARCHAR(40) NOT NULL,
  role VARCHAR(20) NOT NULL,
  relationship VARCHAR(40)
);

-- 家属/志愿者代约：标记这份预约由哪位照护者安排（null=老人自己或助手约的）
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS arranged_by VARCHAR(40);

-- 陪同本次复诊的照护者 id（null=未确认陪同或老人自约）；照护端据此决定是否显示出发建议
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS accompanied_by VARCHAR(40);

-- 协同照护端定向通知：代约结果、老人改动、途中求助、紧急等，发给指定的照护者账号
CREATE TABLE IF NOT EXISTS care_notifications (
  id VARCHAR(50) PRIMARY KEY,
  caregiver_id VARCHAR(40) NOT NULL,
  elder_user_id VARCHAR(40) NOT NULL,
  content VARCHAR(500) NOT NULL,
  kind VARCHAR(20) NOT NULL,
  created_at TIMESTAMP NOT NULL
);

-- 老人端“健康备忘”：复诊助手中老人托付的健康/复诊相关小记；status ACTIVE=进行中 / DONE=已完成 / DELETED=已删除
CREATE TABLE IF NOT EXISTS memos (
  id VARCHAR(50) PRIMARY KEY,
  user_id VARCHAR(40) NOT NULL,
  text VARCHAR(300) NOT NULL,
  remind_at TIMESTAMP,
  status VARCHAR(20) NOT NULL,
  created_at TIMESTAMP NOT NULL
);

-- 重复提醒规则：null=只提醒一次；DAILY=每天 / WEEKLY=每周 / MONTHLY=每月。
-- remind_at 存“下一次到点”的日期时间，同时充当星期几（WEEKLY）/几号（MONTHLY）的锚点。
ALTER TABLE memos ADD COLUMN IF NOT EXISTS repeat_rule VARCHAR(20);

-- 老人上报的实测数值（血压/血糖/心率…）：和 memos（“要做的事”，带提醒、可完成可删除）分家，
-- 这里记的是“已经量到的数”，只增不删，用来回查最近几次。有数值记 value_num，只有说法（“有点高”）记 value_text。
CREATE TABLE IF NOT EXISTS health_records (
  id VARCHAR(50) PRIMARY KEY,
  user_id VARCHAR(40) NOT NULL,
  item VARCHAR(40) NOT NULL,
  value_num DECIMAL(8,2),
  value_text VARCHAR(60) NOT NULL,
  unit VARCHAR(20) NOT NULL,
  raw_text VARCHAR(200),
  conversation_id VARCHAR(50),
  recorded_at TIMESTAMP NOT NULL
);
