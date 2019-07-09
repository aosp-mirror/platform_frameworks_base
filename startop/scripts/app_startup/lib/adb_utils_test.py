import adb_utils

# pip imports
import pytest

def test_parse_time_to_milliseconds():
  # Act
  result1 = adb_utils.parse_time_to_milliseconds('+1s7ms')
  result2 = adb_utils.parse_time_to_milliseconds('+523ms')

  # Assert
  assert result1 == 1007
  assert result2 == 523

if __name__ == '__main__':
  pytest.main()
